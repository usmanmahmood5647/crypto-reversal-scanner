package com.hashimali.cryptoreversal;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    static final String BASE = "https://data-api.binance.vision";
    LinearLayout container;
    TextView status, stats;
    Button scanButton, historyButton;
    DB db;
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Handler handler = new Handler(Looper.getMainLooper());
    boolean historyMode = false;
    ArrayList<Trade> currentTradesList = new ArrayList<>();

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        container = findViewById(R.id.resultsContainer);
        status = findViewById(R.id.status);
        stats = findViewById(R.id.stats);
        scanButton = findViewById(R.id.scanButton);
        historyButton = findViewById(R.id.historyButton);
        db = new DB(this);

        scanButton.setOnClickListener(v -> scan());
        historyButton.setOnClickListener(v -> {
            historyMode = !historyMode;
            if (historyMode) {
                showHistory();
            } else {
                showTrades(currentTradesList);
            }
        });

        updateStats();

        // Check old pending predictions and retroactively ensure prediction entries exist on app start
        updatePendingPredictions();

        // Automatically track open trades and verify completed timeframe predictions every 60 seconds
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                trackOpenTradesAndPredictions();
                handler.postDelayed(this, 60_000);
            }
        }, 5000);
    }

    void scan() {
        historyMode = false;
        scanButton.setEnabled(false);
        status.setText("Scanning market...");
        container.removeAllViews();

        // Update existing pending predictions when a new scan starts
        updatePendingPredictions();

        executor.execute(() -> {
            try {
                JSONArray tickers = getJsonArray("/api/v3/ticker/24hr");
                ArrayList<JSONObject> candidates = new ArrayList<>();

                for (int i=0; i<tickers.length(); i++) {
                    JSONObject t = tickers.getJSONObject(i);
                    String s = t.getString("symbol");
                    if (!s.endsWith("USDT")) continue;
                    if (s.contains("UPUSDT") || s.contains("DOWNUSDT")
                            || s.contains("BULLUSDT") || s.contains("BEARUSDT")) continue;
                    double qv = t.optDouble("quoteVolume", 0);
                    if (qv < 10_000_000) continue;
                    t.put("_qv", qv);
                    candidates.add(t);
                }

                candidates.sort((a,b) -> Double.compare(b.optDouble("_qv"), a.optDouble("_qv")));
                if (candidates.size() > 30) candidates.subList(30, candidates.size()).clear();

                ArrayList<Trade> trades = new ArrayList<>();

                for (JSONObject t : candidates) {
                    String symbol = t.getString("symbol");
                    try {
                        Frame f15 = analyze(symbol, "15m");
                        Frame f1 = analyze(symbol, "1h");
                        Frame f4 = analyze(symbol, "4h");
                        if (f15 == null || f1 == null || f4 == null) continue;

                        double ch24 = t.optDouble("priceChangePercent", 0);
                        Score sc = score(f15, f1, f4, ch24);
                        if (sc.score < 60) continue;

                        Trade tr = plan(symbol, sc, f15, f1, f4, ch24);
                        if (tr != null) trades.add(tr);
                    } catch(Exception ignored) {}
                }

                trades.sort((a,b) -> Double.compare(b.score, a.score));
                if (trades.size() > 15) trades.subList(15, trades.size()).clear();

                // Save new signals and automatically create 15m/1h/4h/24h prediction records.
                // Avoids creating duplicate signals for the same symbol & direction.
                for (Trade tr : trades) {
                    if (!db.hasOpenSignal(tr.symbol, tr.direction)) {
                        long id = db.insertTrade(tr);
                        tr.id = id;
                    } else {
                        tr.id = db.getTradeId(tr.symbol, tr.direction);
                    }
                }

                // Check predictions once scan completes
                db.ensurePredictionsForExistingTrades();
                evaluatePendingPredictionsInternal();

                runOnUiThread(() -> {
                    scanButton.setEnabled(true);
                    status.setText("Scan complete: " + trades.size() + " candidates");
                    currentTradesList = trades;
                    showTrades(trades);
                    updateStats();
                });

            } catch(Exception e) {
                runOnUiThread(() -> {
                    scanButton.setEnabled(true);
                    status.setText("Error: " + e.getMessage());
                });
            }
        });
    }

    JSONArray getJsonArray(String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(BASE + path).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        c.setRequestMethod("GET");
        if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
        StringBuilder s = new StringBuilder();
        String line;
        while((line=r.readLine())!=null) s.append(line);
        r.close();
        return new JSONArray(s.toString());
    }

    JSONArray klines(String symbol, String tf) throws Exception {
        return getJsonArray("/api/v3/klines?symbol=" + symbol + "&interval=" + tf + "&limit=100");
    }

    Frame analyze(String symbol, String tf) throws Exception {
        JSONArray a = klines(symbol, tf);
        if (a.length() < 35) return null;

        // Remove currently forming candle.
        int n = a.length() - 1;
        double[] close = new double[n], high = new double[n], low = new double[n],
                open = new double[n], vol = new double[n];

        for(int i=0;i<n;i++){
            JSONArray x=a.getJSONArray(i);
            open[i]=x.getDouble(1);
            high[i]=x.getDouble(2);
            low[i]=x.getDouble(3);
            close[i]=x.getDouble(4);
            vol[i]=x.getDouble(5);
        }

        double rsi = rsi(close,14);
        double prevRsi = rsi(Arrays.copyOf(close,n-1),14);
        double price=close[n-1];

        double move = ((price-close[n-5])/close[n-5])*100.0;
        double avgv=0;
        for(int i=n-21;i<n-1;i++) avgv+=vol[i];
        avgv/=20.0;
        double vr=avgv>0?vol[n-1]/avgv:1;

        double rh=0, rl=Double.MAX_VALUE, ph=0, pl=Double.MAX_VALUE;
        for(int i=n-12;i<n;i++){ rh=Math.max(rh,high[i]); rl=Math.min(rl,low[i]); }
        for(int i=n-12;i<n-1;i++){ ph=Math.max(ph,high[i]); pl=Math.min(pl,low[i]); }

        double range=Math.max(high[n-1]-low[n-1], price*0.000001);
        double uw=(high[n-1]-Math.max(open[n-1],close[n-1]))/range*100;
        double lw=(Math.min(open[n-1],close[n-1])-low[n-1])/range*100;

        boolean hs=high[n-1]>ph && close[n-1]<ph;
        boolean ls=low[n-1]<pl && close[n-1]>pl;

        return new Frame(price,move,rsi,prevRsi,vr,rh,rl,uw,lw,hs,ls);
    }

    double rsi(double[] c,int p){
        if(c.length<=p) return 50;
        double gain=0,loss=0;
        for(int i=1;i<=p;i++){double d=c[i]-c[i-1]; if(d>=0) gain+=d; else loss-=d;}
        double ag=gain/p, al=loss/p;
        for(int i=p+1;i<c.length;i++){
            double d=c[i]-c[i-1];
            ag=(ag*(p-1)+Math.max(d,0))/p;
            al=(al*(p-1)+Math.max(-d,0))/p;
        }
        if(al==0) return 100;
        return 100-(100/(1+(ag/al)));
    }

    Score score(Frame a, Frame b, Frame c, double ch24){
        double L=0,S=0;
        ArrayList<String> lr=new ArrayList<>(), sr=new ArrayList<>();

        if(ch24<=-15){L+=20;lr.add("24H dump >15%");}
        else if(ch24<=-10){L+=15;lr.add("24H dump >10%");}
        else if(ch24<=-7){L+=10;lr.add("24H dump >7%");}

        if(ch24>=15){S+=20;sr.add("24H pump >15%");}
        else if(ch24>=10){S+=15;sr.add("24H pump >10%");}
        else if(ch24>=7){S+=10;sr.add("24H pump >7%");}

        if(a.move<=-2){L+=10;lr.add("15m sharp dump");}
        if(b.move<=-4){L+=10;lr.add("1H sharp dump");}
        if(c.move<=-7){L+=10;lr.add("4H sharp dump");}

        if(a.move>=2){S+=10;sr.add("15m sharp pump");}
        if(b.move>=4){S+=10;sr.add("1H sharp pump");}
        if(c.move>=7){S+=10;sr.add("4H sharp pump");}

        if(a.rsi<=25){L+=15;lr.add("15m deeply oversold");}
        else if(a.rsi<=30){L+=10;lr.add("15m oversold");}
        if(b.rsi<=30){L+=10;lr.add("1H oversold");}

        if(a.rsi>=75){S+=15;sr.add("15m deeply overbought");}
        else if(a.rsi>=70){S+=10;sr.add("15m overbought");}
        if(b.rsi>=70){S+=10;sr.add("1H overbought");}

        if(a.vr>=3){L+=15;S+=15;lr.add("15m volume >3x");sr.add("15m volume >3x");}
        else if(a.vr>=2){L+=10;S+=10;lr.add("15m volume >2x");sr.add("15m volume >2x");}
        else if(a.vr>=1.5){L+=5;S+=5;lr.add("volume expanding");sr.add("volume expanding");}

        if(a.ls){L+=20;lr.add("15m LOW liquidity sweep");}
        else if(b.ls){L+=15;lr.add("1H LOW liquidity sweep");}
        if(a.hs){S+=20;sr.add("15m HIGH liquidity sweep");}
        else if(b.hs){S+=15;sr.add("1H HIGH liquidity sweep");}

        if(a.lw>=40){L+=10;lr.add("strong lower rejection");}
        else if(a.lw>=25){L+=5;lr.add("lower rejection");}
        if(a.uw>=40){S+=10;sr.add("strong upper rejection");}
        else if(a.uw>=25){S+=5;sr.add("upper rejection");}

        if(a.rsi>a.prevRsi){L+=5;lr.add("15m RSI recovering");}
        if(a.rsi<a.prevRsi){S+=5;sr.add("15m RSI weakening");}

        if(L>=S) return new Score("LONG",L,join(lr));
        return new Score("SHORT",S,join(sr));
    }

    static String join(ArrayList<String> x){
        StringBuilder s=new StringBuilder();
        for(String v:x){if(s.length()>0)s.append(" | ");s.append(v);}
        return s.toString();
    }

    Trade plan(String symbol, Score sc, Frame a, Frame b, Frame c, double ch24){
        double entry=a.price, stop, tp1,tp2,tp3,risk;
        if(sc.direction.equals("LONG")){
            double support=Math.min(a.low,b.low);
            stop=support*0.995;
            risk=entry-stop;
            if(risk<=0) return null;
            tp1=entry+risk*1.5; tp2=entry+risk*2.5; tp3=entry+risk*4;
        } else {
            double resistance=Math.max(a.high,b.high);
            stop=resistance*1.005;
            risk=stop-entry;
            if(risk<=0) return null;
            tp1=entry-risk*1.5; tp2=entry-risk*2.5; tp3=entry-risk*4;
        }
        double riskPct=Math.abs(entry-stop)/entry*100;
        return new Trade(symbol,sc.direction,sc.score,"15m/1H/4H",ch24,a.move,b.move,c.move,
                a.rsi,a.vr,entry,stop,tp1,tp2,tp3,riskPct,sc.reason);
    }

    void showTrades(ArrayList<Trade> trades){
        container.removeAllViews();
        if(trades.isEmpty()){
            TextView x=txt("No setup passed the score filter.\nTry again after a few minutes.");
            container.addView(x); return;
        }
        for(Trade t:trades) addTradeCard(t,false);
    }

    void addTradeCard(Trade t, boolean hist){
        LinearLayout card=new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(18,18,18,18);
        GradientUtil.round(card, 0xFFF4F6F8);

        TextView head=txt(t.symbol.replace("USDT","")+"  "+(t.direction.equals("LONG")?"🚀 LONG":"🔻 SHORT")+"  | Score "+fmt(t.score));
        head.setTextSize(18); head.setTypeface(null, Typeface.BOLD);
        card.addView(head);

        card.addView(txt(
                "24H: "+fmt(t.change24)+"%   15m: "+fmt(t.move15)+"%   1H: "+fmt(t.move1)+"%   4H: "+fmt(t.move4)+"%\n"+
                "RSI 15m: "+fmt(t.rsi)+"   Volume: "+fmt(t.volX)+"x\n\n"+
                "ENTRY: "+fmtP(t.entry)+"\n"+
                "SL:    "+fmtP(t.sl)+"   Risk: "+fmt(t.riskPct)+"%\n"+
                "TP1:   "+fmtP(t.tp1)+"   (1:1.5)\n"+
                "TP2:   "+fmtP(t.tp2)+"   (1:2.5)\n"+
                "TP3:   "+fmtP(t.tp3)+"   (1:4.0)\n\n"+
                "Reason: "+t.reason+"\n"+
                "Status: "+t.status
        ));

        // Add predictions section display to UI card
        card.addView(createPredictionView(t));

        if(!hist){
            Button b=new Button(this);
            b.setText("TRACK THIS SIGNAL");
            b.setOnClickListener(v -> {
                if(!db.hasOpenSignal(t.symbol,t.direction)){
                    long id = db.insertTrade(t);
                    t.id = id;
                    t.status="OPEN";
                    Toast.makeText(this,"Signal saved to history",Toast.LENGTH_SHORT).show();
                    updateStats();
                } else {
                    Toast.makeText(this,"Signal already being tracked",Toast.LENGTH_SHORT).show();
                }
            });
            card.addView(b);
        }
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(0,0,0,14);
        container.addView(card,p);
    }

    View createPredictionView(Trade t) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(0, 10, 0, 10);

        TextView title = new TextView(this);
        title.setText("PREDICTIONS:");
        title.setTextSize(13);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(0xFF333333);
        layout.addView(title);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 4, 0, 4);

        Map<String, Prediction> map = t.id > 0 ? db.getPredictionsForTrade(t.id) : new HashMap<>();

        String[] tfs = new String[]{"15m", "1h", "4h", "24h"};
        String[] tfLabels = new String[]{"15m", "1H", "4H", "24H"};

        for (int i = 0; i < tfs.length; i++) {
            String key = tfs[i];
            String label = tfLabels[i];
            String statusVal = "PENDING";

            if (map.containsKey(key)) {
                statusVal = map.get(key).status;
            }

            TextView tv = new TextView(this);
            tv.setText(label + ": " + statusVal);
            tv.setTextSize(12);
            tv.setTypeface(null, Typeface.BOLD);
            tv.setPadding(0, 0, 14, 0);

            if ("CORRECT".equals(statusVal)) {
                tv.setTextColor(0xFF2E7D32); // Green for CORRECT
            } else if ("FALSE".equals(statusVal)) {
                tv.setTextColor(0xFFD32F2F); // Red for FALSE
            } else {
                tv.setTextColor(0xFF757575); // Neutral Gray for PENDING
            }

            row.addView(tv);
        }

        layout.addView(row);
        return layout;
    }

    TextView txt(String s){
        TextView t=new TextView(this);
        t.setText(s); t.setTextSize(14); t.setPadding(4,4,4,4);
        return t;
    }

    String fmt(double x){return String.format(Locale.US,"%.2f",x);}
    String fmtP(double x){
        if(x>=1000) return String.format(Locale.US,"%.2f",x);
        if(x>=1) return String.format(Locale.US,"%.4f",x);
        return String.format(Locale.US,"%.8f",x);
    }

    void showHistory(){
        container.removeAllViews();
        ArrayList<Trade> list=db.history();
        if(list.isEmpty()){container.addView(txt("No trade history yet.")); return;}
        for(Trade t:list) addTradeCard(t,true);
        updateStats();
    }

    void updateStats(){
        Stats s=db.stats();
        stats.setText("Win rate: "+fmt(s.winRate)+"%  | Wins: "+s.wins+" | Losses: "+s.losses+" | Open: "+s.open);
    }

    void trackOpenTradesAndPredictions() {
        executor.execute(() -> {
            try {
                // 1. Track open trades for TP1 and SL
                ArrayList<Trade> open = db.openTrades();
                for (Trade t : open) {
                    double price = getPrice(t.symbol);
                    if (t.direction.equals("LONG")) {
                        if (price <= t.sl) db.closeTrade(t.id, "LOSS", price);
                        else if (price >= t.tp1) db.closeTrade(t.id, "WIN", price);
                    } else {
                        if (price >= t.sl) db.closeTrade(t.id, "LOSS", price);
                        else if (price <= t.tp1) db.closeTrade(t.id, "WIN", price);
                    }
                }

                // 2. Retrofit & evaluate pending timeframe predictions
                db.ensurePredictionsForExistingTrades();
                evaluatePendingPredictionsInternal();

                runOnUiThread(() -> {
                    updateStats();
                    if (historyMode) {
                        showHistory();
                    }
                });
            } catch (Exception ignored) {}
        });
    }

    void updatePendingPredictions() {
        executor.execute(() -> {
            try {
                db.ensurePredictionsForExistingTrades();
                boolean updated = evaluatePendingPredictionsInternal();
                if (updated) {
                    runOnUiThread(() -> {
                        if (historyMode) {
                            showHistory();
                        }
                    });
                }
            } catch (Exception ignored) {}
        });
    }

    boolean evaluatePendingPredictionsInternal() {
        ArrayList<Prediction> pendingList = db.getPendingPredictions();
        if (pendingList.isEmpty()) return false;

        long now = System.currentTimeMillis();

        // Only evaluate predictions whose target timeframe duration has completed
        ArrayList<Prediction> readyToEvaluate = new ArrayList<>();
        for (Prediction p : pendingList) {
            if (now >= p.evaluateAt) {
                readyToEvaluate.add(p);
            }
        }

        if (readyToEvaluate.isEmpty()) return false;

        // Group predictions by symbol to minimize HTTP requests
        Map<String, ArrayList<Prediction>> bySymbol = new HashMap<>();
        for (Prediction p : readyToEvaluate) {
            if (!bySymbol.containsKey(p.symbol)) {
                bySymbol.put(p.symbol, new ArrayList<>());
            }
            bySymbol.get(p.symbol).add(p);
        }

        boolean updatedAny = false;

        for (Map.Entry<String, ArrayList<Prediction>> entry : bySymbol.entrySet()) {
            String symbol = entry.getKey();
            ArrayList<Prediction> preds = entry.getValue();

            try {
                double currentPrice = getLatestPrice(symbol);

                for (Prediction p : preds) {
                    String result = evaluatePrediction(p.direction, p.entryPrice, currentPrice);
                    if ("CORRECT".equals(result) || "FALSE".equals(result)) {
                        db.markPredictionResult(p.id, result, currentPrice, System.currentTimeMillis());
                        updatedAny = true;
                    }
                }
            } catch (Exception ignored) {}
        }

        return updatedAny;
    }

    String evaluatePrediction(String direction, double entryPrice, double currentPrice) {
        if ("LONG".equalsIgnoreCase(direction)) {
            if (currentPrice > entryPrice) {
                return "CORRECT";
            } else if (currentPrice < entryPrice) {
                return "FALSE";
            } else {
                return "PENDING"; // Keep pending if price equals entry price exactly
            }
        } else if ("SHORT".equalsIgnoreCase(direction)) {
            if (currentPrice < entryPrice) {
                return "CORRECT";
            } else if (currentPrice > entryPrice) {
                return "FALSE";
            } else {
                return "PENDING";
            }
        }
        return "PENDING";
    }

    double getLatestPrice(String symbol) throws Exception {
        return getPrice(symbol);
    }

    double getPrice(String symbol) throws Exception {
        JSONArray a = getJsonArray("/api/v3/ticker/price?symbol=" + symbol);
        return a.getJSONObject(0).getDouble("price");
    }

    static class Frame{
        double price,move,rsi,prevRsi,vr,high,low,uw,lw;
        boolean hs,ls;
        Frame(double p,double m,double r,double pr,double v,double h,double l,double u,double lo,boolean hs,boolean ls){
            price=p;move=m;rsi=r;prevRsi=pr;vr=v;high=h;low=l;uw=u;lw=lo;this.hs=hs;this.ls=ls;
        }
    }

    static class Score{
        String direction,reason; double score;
        Score(String d,double s,String r){direction=d;score=s;reason=r;}
    }

    static class Trade{
        long id; String symbol,direction,tf,status,reason;
        double score,change24,move15,move1,move4,rsi,volX,entry,sl,tp1,tp2,tp3,riskPct,exit;
        long createdAt, closedAt;

        Trade(String s,String d,double sc,String tf,double c24,double m15,double m1,double m4,double r,double v,
              double e,double sl,double t1,double t2,double t3,double rp,String rs){
            symbol=s;direction=d;score=sc;this.tf=tf;change24=c24;move15=m15;move1=m1;move4=m4;rsi=r;volX=v;
            entry=e;this.sl=sl;tp1=t1;tp2=t2;tp3=t3;riskPct=rp;reason=rs;status="OPEN";
            createdAt=System.currentTimeMillis();
        }
    }

    static class Prediction {
        long id;
        long tradeId;
        String symbol;
        String direction;
        String timeframe;
        double entryPrice;
        long createdAt;
        long evaluateAt;
        double evaluatedPrice;
        String status;
        long evaluatedAt;

        Prediction(long id, long tradeId, String symbol, String direction, String timeframe,
                   double entryPrice, long createdAt, long evaluateAt,
                   double evaluatedPrice, String status, long evaluatedAt) {
            this.id = id;
            this.tradeId = tradeId;
            this.symbol = symbol;
            this.direction = direction;
            this.timeframe = timeframe;
            this.entryPrice = entryPrice;
            this.createdAt = createdAt;
            this.evaluateAt = evaluateAt;
            this.evaluatedPrice = evaluatedPrice;
            this.status = status;
            this.evaluatedAt = evaluatedAt;
        }
    }

    static class Stats{int wins,losses,open;double winRate;Stats(int w,int l,int o){wins=w;losses=l;open=o;winRate=(w+l)==0?0:(w*100.0/(w+l));}}

    static class DB extends SQLiteOpenHelper {
        private static final String DB_NAME = "signals.db";
        private static final int DB_VERSION = 2;

        DB(Context c) {
            super(c, DB_NAME, null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase d) {
            d.execSQL("CREATE TABLE IF NOT EXISTS trades(" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, symbol TEXT, direction TEXT, score REAL, tf TEXT, " +
                    "change24 REAL, move15 REAL, move1 REAL, move4 REAL, rsi REAL, volx REAL, " +
                    "entry REAL, sl REAL, tp1 REAL, tp2 REAL, tp3 REAL, risk REAL, reason TEXT, " +
                    "status TEXT, exit REAL, created INTEGER, closed INTEGER)");

            d.execSQL("CREATE TABLE IF NOT EXISTS predictions(" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, trade_id INTEGER, symbol TEXT, direction TEXT, " +
                    "timeframe TEXT, entry_price REAL, created_at INTEGER, evaluate_at INTEGER, " +
                    "evaluated_price REAL, status TEXT, evaluated_at INTEGER, UNIQUE(trade_id, timeframe))");
        }

        @Override
        public void onUpgrade(SQLiteDatabase d, int oldVersion, int newVersion) {
            if (oldVersion < 2) {
                d.execSQL("CREATE TABLE IF NOT EXISTS predictions(" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, trade_id INTEGER, symbol TEXT, direction TEXT, " +
                        "timeframe TEXT, entry_price REAL, created_at INTEGER, evaluate_at INTEGER, " +
                        "evaluated_price REAL, status TEXT, evaluated_at INTEGER, UNIQUE(trade_id, timeframe))");
            }
        }

        boolean hasOpenSignal(String s, String dir) {
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT id FROM trades WHERE symbol=? AND direction=? AND status='OPEN' LIMIT 1",
                    new String[]{s, dir});
            boolean x = c.moveToFirst();
            c.close();
            return x;
        }

        long getTradeId(String s, String dir) {
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT id FROM trades WHERE symbol=? AND direction=? ORDER BY created DESC LIMIT 1",
                    new String[]{s, dir});
            long id = -1;
            if (c.moveToFirst()) {
                id = c.getLong(0);
            }
            c.close();
            return id;
        }

        long insertTrade(Trade t) {
            ContentValues v = new ContentValues();
            v.put("symbol", t.symbol);
            v.put("direction", t.direction);
            v.put("score", t.score);
            v.put("tf", t.tf);
            v.put("change24", t.change24);
            v.put("move15", t.move15);
            v.put("move1", t.move1);
            v.put("move4", t.move4);
            v.put("rsi", t.rsi);
            v.put("volx", t.volX);
            v.put("entry", t.entry);
            v.put("sl", t.sl);
            v.put("tp1", t.tp1);
            v.put("tp2", t.tp2);
            v.put("tp3", t.tp3);
            v.put("risk", t.riskPct);
            v.put("reason", t.reason);
            v.put("status", "OPEN");
            long now = t.createdAt > 0 ? t.createdAt : System.currentTimeMillis();
            v.put("created", now);

            long id = getWritableDatabase().insert("trades", null, v);
            t.id = id;
            t.createdAt = now;

            if (id > 0) {
                createPredictionsForSignal(id, t.symbol, t.direction, t.entry, now);
            }
            return id;
        }

        void createPredictionsForSignal(long tradeId, String symbol, String direction, double entryPrice, long createdAt) {
            SQLiteDatabase db = getWritableDatabase();
            String[] tfs = new String[]{"15m", "1h", "4h", "24h"};
            long[] offsets = new long[]{
                    15 * 60 * 1000L,       // 15m
                    60 * 60 * 1000L,       // 1h
                    4 * 60 * 60 * 1000L,   // 4h
                    24 * 60 * 60 * 1000L   // 24h
            };

            db.beginTransaction();
            try {
                for (int i = 0; i < tfs.length; i++) {
                    ContentValues cv = new ContentValues();
                    cv.put("trade_id", tradeId);
                    cv.put("symbol", symbol);
                    cv.put("direction", direction);
                    cv.put("timeframe", tfs[i]);
                    cv.put("entry_price", entryPrice);
                    cv.put("created_at", createdAt);
                    cv.put("evaluate_at", createdAt + offsets[i]);
                    cv.put("evaluated_price", 0.0);
                    cv.put("status", "PENDING");
                    cv.put("evaluated_at", 0);

                    db.insertWithOnConflict("predictions", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }

        void ensurePredictionsForExistingTrades() {
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT id, symbol, direction, entry, created FROM trades WHERE id NOT IN (SELECT DISTINCT trade_id FROM predictions)",
                    null);
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String symbol = c.getString(1);
                String dir = c.getString(2);
                double entry = c.getDouble(3);
                long created = c.getLong(4);
                if (created <= 0) created = System.currentTimeMillis();
                createPredictionsForSignal(id, symbol, dir, entry, created);
            }
            c.close();
        }

        ArrayList<Prediction> getPendingPredictions() {
            ArrayList<Prediction> list = new ArrayList<>();
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT id, trade_id, symbol, direction, timeframe, entry_price, created_at, evaluate_at, evaluated_price, status, evaluated_at " +
                            "FROM predictions WHERE status='PENDING'", null);
            while (c.moveToNext()) {
                list.add(new Prediction(
                        c.getLong(0), c.getLong(1), c.getString(2), c.getString(3),
                        c.getString(4), c.getDouble(5), c.getLong(6), c.getLong(7),
                        c.getDouble(8), c.getString(9), c.getLong(10)
                ));
            }
            c.close();
            return list;
        }

        void markPredictionResult(long predictionId, String status, double evaluatedPrice, long evaluatedAt) {
            ContentValues cv = new ContentValues();
            cv.put("status", status);
            cv.put("evaluated_price", evaluatedPrice);
            cv.put("evaluated_at", evaluatedAt);
            getWritableDatabase().update("predictions", cv, "id=?", new String[]{String.valueOf(predictionId)});
        }

        Map<String, Prediction> getPredictionsForTrade(long tradeId) {
            Map<String, Prediction> map = new LinkedHashMap<>();
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT id, trade_id, symbol, direction, timeframe, entry_price, created_at, evaluate_at, evaluated_price, status, evaluated_at " +
                            "FROM predictions WHERE trade_id=? ORDER BY evaluate_at ASC",
                    new String[]{String.valueOf(tradeId)});
            while (c.moveToNext()) {
                Prediction p = new Prediction(
                        c.getLong(0), c.getLong(1), c.getString(2), c.getString(3),
                        c.getString(4), c.getDouble(5), c.getLong(6), c.getLong(7),
                        c.getDouble(8), c.getString(9), c.getLong(10)
                );
                map.put(p.timeframe, p);
            }
            c.close();
            return map;
        }

        ArrayList<Trade> openTrades(){return query("status='OPEN'");}
        ArrayList<Trade> history(){return query("1=1");}

        ArrayList<Trade> query(String where){
            ArrayList<Trade> out=new ArrayList<>();
            Cursor c=getReadableDatabase().rawQuery("SELECT * FROM trades WHERE "+where+" ORDER BY created DESC",null);
            while(c.moveToNext()){
                Trade t=new Trade(c.getString(1),c.getString(2),c.getDouble(3),c.getString(4),c.getDouble(5),c.getDouble(6),c.getDouble(7),c.getDouble(8),c.getDouble(9),c.getDouble(10),
                        c.getDouble(11),c.getDouble(12),c.getDouble(13),c.getDouble(14),c.getDouble(15),c.getDouble(16),c.getString(17));
                t.id=c.getLong(0);
                t.status=c.getString(18);
                if (c.getColumnCount() > 19) t.exit = c.getDouble(19);
                if (c.getColumnCount() > 20) t.createdAt = c.getLong(20);
                if (c.getColumnCount() > 21) t.closedAt = c.getLong(21);
                out.add(t);
            }
            c.close();return out;
        }

        void closeTrade(long id,String status,double exit){
            ContentValues v=new ContentValues();
            v.put("status",status);v.put("exit",exit);v.put("closed",System.currentTimeMillis());
            getWritableDatabase().update("trades",v,"id=?",new String[]{String.valueOf(id)});
        }

        Stats stats(){
            Cursor c=getReadableDatabase().rawQuery("SELECT status,COUNT(*) FROM trades GROUP BY status",null);
            int w=0,l=0,o=0;
            while(c.moveToNext()){String s=c.getString(0);int n=c.getInt(1);if("WIN".equals(s))w=n;else if("LOSS".equals(s))l=n;else if("OPEN".equals(s))o=n;}
            c.close();return new Stats(w,l,o);
        }
    }

    static class GradientUtil{
        static void round(View v,int color){
            android.graphics.drawable.GradientDrawable g=new android.graphics.drawable.GradientDrawable();
            g.setColor(color);g.setCornerRadius(18);v.setBackground(g);
        }
    }
}
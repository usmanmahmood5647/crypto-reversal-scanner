
package com.hashimali.cryptoreversal;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import android.content.Context;
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
            if (historyMode) showHistory();
            else scan();
        });

        updateStats();

        // Track existing open signals whenever app is running.
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                trackOpenTrades();
                handler.postDelayed(this, 60_000);
            }
        }, 5000);
    }

    void scan() {
        historyMode = false;
        scanButton.setEnabled(false);
        status.setText("Scanning market...");
        container.removeAllViews();

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

                // Save new signals, but avoid duplicate open signals for same symbol/direction.
                for (Trade tr : trades) {
                    if (!db.hasOpenSignal(tr.symbol, tr.direction)) {
                        db.insertTrade(tr);
                    }
                }

                runOnUiThread(() -> {
                    scanButton.setEnabled(true);
                    status.setText("Scan complete: " + trades.size() + " candidates");
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
        head.setTextSize(18); head.setTypeface(null,1);
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

        if(!hist){
            Button b=new Button(this);
            b.setText("TRACK THIS SIGNAL");
            b.setOnClickListener(v -> {
                if(!db.hasOpenSignal(t.symbol,t.direction)){
                    db.insertTrade(t);
                    t.status="OPEN";
                    Toast.makeText(this,"Signal saved to history",Toast.LENGTH_SHORT).show();
                    updateStats();
                }
            });
            card.addView(b);
        }
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(0,0,0,14);
        container.addView(card,p);
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

    void trackOpenTrades(){
        executor.execute(() -> {
            try {
                ArrayList<Trade> open=db.openTrades();
                for(Trade t:open){
                    double price=getPrice(t.symbol);
                    if(t.direction.equals("LONG")){
                        if(price<=t.sl) db.closeTrade(t.id,"LOSS",price);
                        else if(price>=t.tp1) db.closeTrade(t.id,"WIN",price);
                    }else{
                        if(price>=t.sl) db.closeTrade(t.id,"LOSS",price);
                        else if(price<=t.tp1) db.closeTrade(t.id,"WIN",price);
                    }
                }
                runOnUiThread(this::updateStats);
            }catch(Exception ignored){}
        });
    }

    double getPrice(String symbol) throws Exception{
        JSONArray a=getJsonArray("/api/v3/ticker/price?symbol="+symbol);
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
        double score,change24,move15,move1,move4,rsi,volX,entry,sl,tp1,tp2,tp3,riskPct;
        Trade(String s,String d,double sc,String tf,double c24,double m15,double m1,double m4,double r,double v,
              double e,double sl,double t1,double t2,double t3,double rp,String rs){
            symbol=s;direction=d;score=sc;this.tf=tf;change24=c24;move15=m15;move1=m1;move4=m4;rsi=r;volX=v;
            entry=e;this.sl=sl;tp1=t1;tp2=t2;tp3=t3;riskPct=rp;reason=rs;status="OPEN";
        }
    }

    static class Stats{int wins,losses,open;double winRate;Stats(int w,int l,int o){wins=w;losses=l;open=o;winRate=(w+l)==0?0:(w*100.0/(w+l));}}

    static class DB extends SQLiteOpenHelper{
        DB(Context c){super(c,"signals.db",null,1);}
        public void onCreate(SQLiteDatabase d){
            d.execSQL("CREATE TABLE trades(id INTEGER PRIMARY KEY AUTOINCREMENT,symbol TEXT,direction TEXT,score REAL,tf TEXT,change24 REAL,move15 REAL,move1 REAL,move4 REAL,rsi REAL,volx REAL,entry REAL,sl REAL,tp1 REAL,tp2 REAL,tp3 REAL,risk REAL,reason TEXT,status TEXT,exit REAL,created INTEGER,closed INTEGER)");
        }
        public void onUpgrade(SQLiteDatabase d,int o,int n){}
        boolean hasOpenSignal(String s,String dir){
            Cursor c=getReadableDatabase().rawQuery("SELECT id FROM trades WHERE symbol=? AND direction=? AND status='OPEN' LIMIT 1",new String[]{s,dir});
            boolean x=c.moveToFirst();c.close();return x;
        }
        void insertTrade(Trade t){
            android.content.ContentValues v=new android.content.ContentValues();
            v.put("symbol",t.symbol);v.put("direction",t.direction);v.put("score",t.score);v.put("tf",t.tf);
            v.put("change24",t.change24);v.put("move15",t.move15);v.put("move1",t.move1);v.put("move4",t.move4);
            v.put("rsi",t.rsi);v.put("volx",t.volX);v.put("entry",t.entry);v.put("sl",t.sl);v.put("tp1",t.tp1);v.put("tp2",t.tp2);v.put("tp3",t.tp3);
            v.put("risk",t.riskPct);v.put("reason",t.reason);v.put("status","OPEN");v.put("created",System.currentTimeMillis());
            getWritableDatabase().insert("trades",null,v);
        }
        ArrayList<Trade> openTrades(){return query("status='OPEN'");}
        ArrayList<Trade> history(){return query("1=1");}
        ArrayList<Trade> query(String where){
            ArrayList<Trade> out=new ArrayList<>();
            Cursor c=getReadableDatabase().rawQuery("SELECT * FROM trades WHERE "+where+" ORDER BY created DESC",null);
            while(c.moveToNext()){
                Trade t=new Trade(c.getString(1),c.getString(2),c.getDouble(3),c.getString(4),c.getDouble(5),c.getDouble(6),c.getDouble(7),c.getDouble(8),c.getDouble(9),c.getDouble(10),
                        c.getDouble(11),c.getDouble(12),c.getDouble(13),c.getDouble(14),c.getDouble(15),c.getDouble(16),c.getString(17));
                t.id=c.getLong(0);t.status=c.getString(18);out.add(t);
            }
            c.close();return out;
        }
        void closeTrade(long id,String status,double exit){
            android.content.ContentValues v=new android.content.ContentValues();
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

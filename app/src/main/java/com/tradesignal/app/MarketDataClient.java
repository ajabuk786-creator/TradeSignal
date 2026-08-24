package com.tradesignal.app;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * XAU/USD spot market-data client for TradeSignal v6.
 *
 * Uses Twelve Data's XAU/USD commodity/forex aggregate. This is DATA ONLY:
 * no broker credentials, no order endpoint, no account access.
 */
public final class MarketDataClient {
    private MarketDataClient() {}
    private static final String PREFS="trade_signal";
    private static final String KEY_API="twelve_data_api_key";
    private static volatile String apiKey="demo";

    public static final class Market {
        public final String id,displayName,apiSymbol,note;
        public Market(String id,String displayName,String apiSymbol,String note){this.id=id;this.displayName=displayName;this.apiSymbol=apiSymbol;this.note=note;}
    }

    public static final Market[] MARKETS=new Market[]{
            new Market("XAU","XAU/USD","XAU/USD","Gold Spot / US Dollar")
    };

    public static Market byId(String id){return MARKETS[0];}

    public static void init(Context ctx){
        if(ctx==null)return;
        String saved=ctx.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY_API,"");
        apiKey=(saved==null||saved.trim().isEmpty())?"demo":saved.trim();
    }

    public static void saveApiKey(Context ctx,String key){
        String clean=key==null?"":key.trim();
        ctx.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(KEY_API,clean).apply();
        apiKey=clean.isEmpty()?"demo":clean;
    }

    public static String storedApiKey(Context ctx){
        String x=ctx.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY_API,"");
        return x==null?"":x;
    }

    public static boolean hasUserApiKey(Context ctx){return !storedApiKey(ctx).trim().isEmpty();}

    public static List<SignalEngine.Candle> fetchClosed(Market market,String timeframe,int limit)throws Exception{
        String interval=interval(timeframe);
        long tfMs=intervalMs(timeframe);
        String endpoint="https://api.twelvedata.com/time_series?symbol="+URLEncoder.encode(market.apiSymbol,"UTF-8")
                +"&interval="+URLEncoder.encode(interval,"UTF-8")+"&outputsize="+Math.max(80,Math.min(limit,5000))
                +"&timezone=UTC&format=JSON&apikey="+URLEncoder.encode(apiKey,"UTF-8");
        Exception last=null;
        for(int attempt=1;attempt<=3;attempt++){
            try{
                JSONObject root=new JSONObject(get(endpoint));
                if("error".equalsIgnoreCase(root.optString("status"))||root.has("code")){
                    throw new IOException(root.optString("message","Market-data API error"));
                }
                JSONArray values=root.optJSONArray("values");
                if(values==null||values.length()==0)throw new IOException("No XAU/USD candles returned. Add a Twelve Data key if demo access is unavailable.");
                ArrayList<SignalEngine.Candle> out=new ArrayList<>();
                long now=System.currentTimeMillis();
                SimpleDateFormat fmt=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US);fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
                for(int i=0;i<values.length();i++){
                    JSONObject v=values.getJSONObject(i);
                    Date d=fmt.parse(v.getString("datetime"));if(d==null)continue;
                    long openTime=d.getTime();long closeTime=openTime+tfMs-1;
                    if(closeTime>=now)continue;
                    double open=num(v,"open"),high=num(v,"high"),low=num(v,"low"),close=num(v,"close");
                    double volume=v.has("volume")?v.optDouble("volume",0.0):0.0;
                    if(!finite(open,high,low,close))continue;
                    out.add(new SignalEngine.Candle(openTime,open,high,low,close,volume,closeTime));
                }
                Collections.sort(out,Comparator.comparingLong(c->c.openTime));
                if(out.size()<60)throw new IOException("Not enough closed XAU/USD "+timeframe+" candles returned ("+out.size()+").");
                return out;
            }catch(Exception e){last=e;if(attempt<3)Thread.sleep(700L*attempt);}
        }
        throw last==null?new IOException("Unknown market-data error"):last;
    }

    public static double fetchLastPrice(Market market)throws Exception{
        String endpoint="https://api.twelvedata.com/price?symbol="+URLEncoder.encode(market.apiSymbol,"UTF-8")
                +"&apikey="+URLEncoder.encode(apiKey,"UTF-8");
        JSONObject o=new JSONObject(get(endpoint));
        if("error".equalsIgnoreCase(o.optString("status"))||o.has("code"))throw new IOException(o.optString("message","Price API error"));
        String p=o.optString("price","");
        if(p.isEmpty())throw new IOException("No live XAU/USD price returned");
        return Double.parseDouble(p);
    }

    private static String get(String endpoint)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(endpoint).openConnection();
        c.setRequestMethod("GET");c.setConnectTimeout(12000);c.setReadTimeout(12000);
        c.setRequestProperty("Accept","application/json");c.setRequestProperty("User-Agent","TradeSignal-XAU/6.0");
        int code=c.getResponseCode();InputStream stream=code>=200&&code<300?c.getInputStream():c.getErrorStream();
        String body=readAll(stream);
        if(code==429)throw new IOException("Market-data rate limit reached. Try again shortly.");
        if(code<200||code>=300)throw new IOException("Market-data HTTP "+code+": "+trim(body,180));
        return body;
    }

    private static double num(JSONObject o,String k){try{return Double.parseDouble(o.getString(k));}catch(Exception e){return Double.NaN;}}
    private static boolean finite(double...x){for(double v:x)if(!Double.isFinite(v))return false;return true;}
    private static String interval(String tf){if("5m".equals(tf))return"5min";if("15m".equals(tf))return"15min";if("1h".equals(tf))return"1h";return tf;}
    private static long intervalMs(String tf){if("5m".equals(tf))return 5L*60L*1000L;if("15m".equals(tf))return 15L*60L*1000L;if("1h".equals(tf))return 60L*60L*1000L;return 5L*60L*1000L;}
    private static String readAll(InputStream in)throws IOException{if(in==null)return"";try(InputStream x=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[]b=new byte[8192];int n;while((n=x.read(b))>=0)out.write(b,0,n);return out.toString(StandardCharsets.UTF_8.name());}}
    private static String trim(String s,int n){return s==null?"":s.length()<=n?s:s.substring(0,n);}
}

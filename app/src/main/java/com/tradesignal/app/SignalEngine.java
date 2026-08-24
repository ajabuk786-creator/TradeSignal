package com.tradesignal.app;

import java.util.*;

/**
 * TradeSignal v6 — XAU/USD only.
 * Structure-first 15m context + 5m entry trigger using support/resistance and supply/demand zones.
 * Quality is a rule-quality score (1..10), never a probability of winning.
 */
public final class SignalEngine {
    private SignalEngine(){}

    public static final class Candle{
        public final long openTime,closeTime;public final double open,high,low,close,volume;
        public Candle(long openTime,double open,double high,double low,double close,double volume,long closeTime){this.openTime=openTime;this.open=open;this.high=high;this.low=low;this.close=close;this.volume=volume;this.closeTime=closeTime;}
    }

    public static final class Signal{
        public final String side,rationale,confirmationLabel,amdState,structureKey,regime;
        public final double entry,stopLoss,takeProfit,riskPerUnit,qtyAt2Pct,trailingTrigger,riskReward;
        public final int confidence,setupConfidence,triggerConfidence,qualityScore,votesFor,votesAgainst;
        public final long candleCloseTime;
        public Signal(String side,double entry,double stopLoss,double takeProfit,double riskPerUnit,double trailingTrigger,double riskReward,
                      int qualityScore,int setupConfidence,int triggerConfidence,String confirmationLabel,String zoneText,String structureKey,String regime,String rationale,long candleCloseTime){
            this.side=side;this.entry=entry;this.stopLoss=stopLoss;this.takeProfit=takeProfit;this.riskPerUnit=riskPerUnit;this.qtyAt2Pct=0.0;
            this.trailingTrigger=trailingTrigger;this.riskReward=riskReward;this.qualityScore=qualityScore;this.confidence=qualityScore*10;
            this.setupConfidence=setupConfidence;this.triggerConfidence=triggerConfidence;this.votesFor=qualityScore;this.votesAgainst=10-qualityScore;
            this.confirmationLabel=confirmationLabel;this.amdState=zoneText;this.structureKey=structureKey;this.regime=regime;this.rationale=rationale;this.candleCloseTime=candleCloseTime;
        }
    }

    public static final class Decision{
        public final Signal signal;public final String direction,summary,regime;
        public final int qualityScore,setupConfidence,triggerConfidence;
        public final double support,resistance,demandLow,demandHigh,supplyLow,supplyHigh,lastPrice;
        public Decision(Signal signal,String direction,String regime,int quality,int setup,int trigger,String summary,double support,double resistance,double demandLow,double demandHigh,double supplyLow,double supplyHigh,double lastPrice){
            this.signal=signal;this.direction=direction;this.regime=regime;this.qualityScore=quality;this.setupConfidence=setup;this.triggerConfidence=trigger;this.summary=summary;
            this.support=support;this.resistance=resistance;this.demandLow=demandLow;this.demandHigh=demandHigh;this.supplyLow=supplyLow;this.supplyHigh=supplyHigh;this.lastPrice=lastPrice;
        }
    }

    private static final class Zone{double low,high;int index;Zone(double low,double high,int index){this.low=low;this.high=high;this.index=index;}}
    private static final class Candidate{String side,type,regime,why;double entry,sl,tp,anchor;int quality,setup,trigger;long time;}

    public static Decision analyzeGold(List<Candle> m5,List<Candle> m15){
        if(m5==null||m15==null||m5.size()<90||m15.size()<80)return new Decision(null,"WAIT","WARMUP",0,0,0,"Need more closed 5m/15m candles.",Double.NaN,Double.NaN,Double.NaN,Double.NaN,Double.NaN,Double.NaN,Double.NaN);
        int i=m5.size()-1,j=m15.size()-1;
        double[] c5=series(m5,'c'),o5=series(m5,'o'),h5=series(m5,'h'),l5=series(m5,'l');
        double[] c15=series(m15,'c'),o15=series(m15,'o'),h15=series(m15,'h'),l15=series(m15,'l');
        double[] atr5=atr(h5,l5,c5,14),atr15=atr(h15,l15,c15,14),e9=ema(c5,9),e20=ema(c5,20),e20_15=ema(c15,20),e50_15=ema(c15,50),rsi5=rsi(c5,14),rsi15=rsi(c15,14);
        if(!finite(atr5[i],atr15[j],e9[i],e20[i],e20_15[j],e50_15[j],rsi5[i],rsi15[j]))return new Decision(null,"WAIT","WARMUP",0,0,0,"Indicators warming up.",Double.NaN,Double.NaN,Double.NaN,Double.NaN,Double.NaN,Double.NaN,c5[i]);

        double price=c5[i],a5=atr5[i],a15=atr15[j];
        double support=nearestPivotBelow(l15,price,j-3,2);if(!Double.isFinite(support))support=min(l15,Math.max(0,j-30),j-2);
        double resistance=nearestPivotAbove(h15,price,j-3,2);if(!Double.isFinite(resistance))resistance=max(h15,Math.max(0,j-30),j-2);
        Zone demand=findDemand(o15,h15,l15,c15,atr15,j,price);Zone supply=findSupply(o15,h15,l15,c15,atr15,j,price);
        if(demand==null)demand=new Zone(support-0.22*a15,support+0.12*a15,j-1);
        if(supply==null)supply=new Zone(resistance-0.12*a15,resistance+0.22*a15,j-1);

        boolean up=e20_15[j]>e50_15[j]&&e20_15[j]>=e20_15[Math.max(0,j-3)]&&c15[j]>=e20_15[j];
        boolean down=e20_15[j]<e50_15[j]&&e20_15[j]<=e20_15[Math.max(0,j-3)]&&c15[j]<=e20_15[j];
        String regime=up?"15m UP":down?"15m DOWN":"15m RANGE";

        Candidate buy=buyCandidate(m5,c5,o5,h5,l5,e9,e20,rsi5,a5,rsi15[j],up,down,support,resistance,demand,supply,i,regime);
        Candidate sell=sellCandidate(m5,c5,o5,h5,l5,e9,e20,rsi5,a5,rsi15[j],up,down,support,resistance,demand,supply,i,regime);
        Candidate best=null;if(buy!=null&&sell!=null)best=buy.quality>=sell.quality?buy:sell;else if(buy!=null)best=buy;else if(sell!=null)best=sell;
        if(best==null){
            String dir=price<=demand.high+0.35*a5?"WATCH BUY":price>=supply.low-0.35*a5?"WATCH SELL":up?"BUY BIAS":down?"SELL BIAS":"WAIT";
            String summary="No confirmed 5m entry yet. Waiting for rejection, liquidity sweep, or breakout-retest at a 15m support/resistance or supply/demand zone.";
            return new Decision(null,dir,regime,0,contextScore(up,down,rsi15[j]),0,summary,support,resistance,demand.low,demand.high,supply.low,supply.high,price);
        }
        Signal s=finish(best,a5,support,resistance,demand,supply);
        if(s==null){
            return new Decision(null,"WAIT",regime,best.quality,best.setup,best.trigger,"A setup formed but the next opposing zone leaves too little reward after structural SL, so the trade is skipped.",support,resistance,demand.low,demand.high,supply.low,supply.high,price);
        }
        return new Decision(s,s.side,regime,s.qualityScore,s.setupConfidence,s.triggerConfidence,s.confirmationLabel+" confirmed. "+s.rationale,support,resistance,demand.low,demand.high,supply.low,supply.high,price);
    }

    private static Candidate buyCandidate(List<Candle> m5,double[] c,double[] o,double[] h,double[] l,double[] e9,double[] e20,double[] rsi,double a5,double rsi15,boolean up,boolean down,double support,double resistance,Zone demand,Zone supply,int i,String regime){
        if(down&&rsi15<38)return null;
        int touch=-1;for(int k=Math.max(1,i-3);k<=i;k++)if(l[k]<=Math.max(demand.high,support+0.18*a5)){touch=k;break;}
        boolean zoneReject=touch>=0&&c[i]>o[i]&&c[i]>e9[i]&&c[i]>=Math.max(demand.high,support)-0.08*a5;
        boolean sweep=false;for(int k=Math.max(2,i-3);k<i;k++)if(l[k]<support-0.05*a5&&c[k]>support&&c[i]>h[k]){sweep=true;break;}
        double breakLevel=max(h,Math.max(0,i-18),Math.max(0,i-5));
        boolean breakoutRetest=false;int bidx=-1;for(int k=Math.max(2,i-4);k<i;k++)if(c[k]>breakLevel+0.05*a5){bidx=k;break;}
        if(bidx>=0)breakoutRetest=l[i]<=breakLevel+0.20*a5&&c[i]>breakLevel&&c[i]>o[i];
        if(!(zoneReject||sweep||breakoutRetest))return null;
        boolean momentum=c[i]>e9[i]&&e9[i]>=e20[i]&&rsi[i]>=47&&rsi[i]<=72;
        double body=Math.abs(c[i]-o[i])/Math.max(h[i]-l[i],1e-9);
        int q=6;if(up)q++;if(momentum)q++;if(body>=0.40)q++;if(sweep||breakoutRetest)q++;
        if(q<7)return null;
        Candidate x=new Candidate();x.side="BUY";x.type=sweep?"Support liquidity sweep + reclaim":breakoutRetest?"Resistance breakout + retest":"Demand/support rejection";x.quality=Math.min(10,q);x.setup=up?85:70;x.trigger=(sweep||breakoutRetest)?90:80;x.entry=c[i];
        double structural=Math.min(min(l,Math.max(0,i-8),i),Math.min(demand.low,support));x.sl=Math.min(x.entry-0.60*a5,structural-0.12*a5);x.anchor=sweep?support:breakoutRetest?breakLevel:demand.low;x.regime=regime;x.time=m5.get(i).closeTime;x.why="15m zone + confirmed 5m bullish reaction; stop sits beyond structural invalidation.";return x;
    }

    private static Candidate sellCandidate(List<Candle> m5,double[] c,double[] o,double[] h,double[] l,double[] e9,double[] e20,double[] rsi,double a5,double rsi15,boolean up,boolean down,double support,double resistance,Zone demand,Zone supply,int i,String regime){
        if(up&&rsi15>62)return null;
        int touch=-1;for(int k=Math.max(1,i-3);k<=i;k++)if(h[k]>=Math.min(supply.low,resistance-0.18*a5)){touch=k;break;}
        boolean zoneReject=touch>=0&&c[i]<o[i]&&c[i]<e9[i]&&c[i]<=Math.min(supply.low,resistance)+0.08*a5;
        boolean sweep=false;for(int k=Math.max(2,i-3);k<i;k++)if(h[k]>resistance+0.05*a5&&c[k]<resistance&&c[i]<l[k]){sweep=true;break;}
        double breakLevel=min(l,Math.max(0,i-18),Math.max(0,i-5));
        boolean breakoutRetest=false;int bidx=-1;for(int k=Math.max(2,i-4);k<i;k++)if(c[k]<breakLevel-0.05*a5){bidx=k;break;}
        if(bidx>=0)breakoutRetest=h[i]>=breakLevel-0.20*a5&&c[i]<breakLevel&&c[i]<o[i];
        if(!(zoneReject||sweep||breakoutRetest))return null;
        boolean momentum=c[i]<e9[i]&&e9[i]<=e20[i]&&rsi[i]<=53&&rsi[i]>=28;
        double body=Math.abs(c[i]-o[i])/Math.max(h[i]-l[i],1e-9);
        int q=6;if(down)q++;if(momentum)q++;if(body>=0.40)q++;if(sweep||breakoutRetest)q++;
        if(q<7)return null;
        Candidate x=new Candidate();x.side="SELL";x.type=sweep?"Resistance liquidity sweep + rejection":breakoutRetest?"Support breakout + retest":"Supply/resistance rejection";x.quality=Math.min(10,q);x.setup=down?85:70;x.trigger=(sweep||breakoutRetest)?90:80;x.entry=c[i];
        double structural=Math.max(max(h,Math.max(0,i-8),i),Math.max(supply.high,resistance));x.sl=Math.max(x.entry+0.60*a5,structural+0.12*a5);x.anchor=sweep?resistance:breakoutRetest?breakLevel:supply.high;x.regime=regime;x.time=m5.get(i).closeTime;x.why="15m zone + confirmed 5m bearish reaction; stop sits beyond structural invalidation.";return x;
    }

    private static Signal finish(Candidate x,double a5,double support,double resistance,Zone demand,Zone supply){
        double risk=Math.abs(x.entry-x.sl);if(!Double.isFinite(risk)||risk<=0)return null;
        double desired="BUY".equals(x.side)?x.entry+1.25*risk:x.entry-1.25*risk;
        double tp=desired;
        if("BUY".equals(x.side)){
            double barrier=Double.POSITIVE_INFINITY;if(resistance>x.entry)barrier=Math.min(barrier,resistance);if(supply.low>x.entry)barrier=Math.min(barrier,supply.low);
            if(Double.isFinite(barrier))tp=Math.min(tp,barrier-0.08*a5);
            if(tp<=x.entry)return null;
        }else{
            double barrier=Double.NEGATIVE_INFINITY;if(support<x.entry)barrier=Math.max(barrier,support);if(demand.high<x.entry)barrier=Math.max(barrier,demand.high);
            if(Double.isFinite(barrier))tp=Math.max(tp,barrier+0.08*a5);
            if(tp>=x.entry)return null;
        }
        double rr=Math.abs(tp-x.entry)/risk;if(rr<1.10)return null;
        double trail="BUY".equals(x.side)?x.entry+0.80*risk:x.entry-0.80*risk;
        String zoneText="Support "+fmt(support)+" · Resistance "+fmt(resistance)+" · Demand "+fmt(demand.low)+"–"+fmt(demand.high)+" · Supply "+fmt(supply.low)+"–"+fmt(supply.high);
        String key=x.type+":"+x.side+":"+Math.round(x.anchor*2.0)/2.0;
        return new Signal(x.side,x.entry,x.sl,tp,risk,trail,rr,x.quality,x.setup,x.trigger,x.type,zoneText,key,x.regime,x.why,x.time);
    }

    private static Zone findDemand(double[] o,double[] h,double[] l,double[] c,double[] atr,int end,double price){Zone best=null;for(int k=Math.max(3,end-45);k<=end-3;k++){double a=atr[k];if(!Double.isFinite(a)||a<=0)continue;double baseBody=Math.abs(c[k]-o[k]);double push=max(c,k+1,Math.min(end,k+3))-h[k];if(baseBody<=0.65*a&&push>=0.90*a){double low=l[k],high=Math.max(o[k],c[k]);if(high<=price+0.30*a&&(best==null||high>best.high))best=new Zone(low,high,k);}}return best;}
    private static Zone findSupply(double[] o,double[] h,double[] l,double[] c,double[] atr,int end,double price){Zone best=null;for(int k=Math.max(3,end-45);k<=end-3;k++){double a=atr[k];if(!Double.isFinite(a)||a<=0)continue;double baseBody=Math.abs(c[k]-o[k]);double drop=l[k]-min(c,k+1,Math.min(end,k+3));if(baseBody<=0.65*a&&drop>=0.90*a){double low=Math.min(o[k],c[k]),high=h[k];if(low>=price-0.30*a&&(best==null||low<best.low))best=new Zone(low,high,k);}}return best;}

    private static int contextScore(boolean up,boolean down,double rsi){int s=(up||down)?70:55;if((up&&rsi>52)||(down&&rsi<48))s+=10;return Math.min(90,s);}
    private static double nearestPivotBelow(double[] a,double price,int end,int wing){double best=Double.NaN;for(int i=wing;i<=end-wing;i++){boolean p=true;for(int k=1;k<=wing;k++)if(a[i]>=a[i-k]||a[i]>=a[i+k]){p=false;break;}if(p&&a[i]<price&&(!Double.isFinite(best)||a[i]>best))best=a[i];}return best;}
    private static double nearestPivotAbove(double[] a,double price,int end,int wing){double best=Double.NaN;for(int i=wing;i<=end-wing;i++){boolean p=true;for(int k=1;k<=wing;k++)if(a[i]<=a[i-k]||a[i]<=a[i+k]){p=false;break;}if(p&&a[i]>price&&(!Double.isFinite(best)||a[i]<best))best=a[i];}return best;}
    private static double[] series(List<Candle> c,char f){double[]x=new double[c.size()];for(int i=0;i<c.size();i++){Candle z=c.get(i);x[i]=f=='o'?z.open:f=='h'?z.high:f=='l'?z.low:f=='v'?z.volume:z.close;}return x;}
    private static double[] ema(double[]v,int p){double[]o=new double[v.length];Arrays.fill(o,Double.NaN);if(v.length<p)return o;double s=0;for(int i=0;i<p;i++)s+=v[i];double cur=s/p;o[p-1]=cur;double k=2.0/(p+1.0);for(int i=p;i<v.length;i++){cur=(v[i]-cur)*k+cur;o[i]=cur;}return o;}
    private static double[] rsi(double[]v,int p){double[]o=new double[v.length];Arrays.fill(o,Double.NaN);if(v.length<=p)return o;double g=0,l=0;for(int i=1;i<=p;i++){double d=v[i]-v[i-1];g+=Math.max(d,0);l+=Math.max(-d,0);}g/=p;l/=p;o[p]=l==0?100:100-100/(1+g/l);for(int i=p+1;i<v.length;i++){double d=v[i]-v[i-1];g=(g*(p-1)+Math.max(d,0))/p;l=(l*(p-1)+Math.max(-d,0))/p;o[i]=l==0?100:100-100/(1+g/l);}return o;}
    private static double[] atr(double[]h,double[]l,double[]c,int p){double[]o=new double[c.length];Arrays.fill(o,Double.NaN);if(c.length<=p)return o;double[]tr=new double[c.length];tr[0]=h[0]-l[0];for(int i=1;i<c.length;i++)tr[i]=Math.max(h[i]-l[i],Math.max(Math.abs(h[i]-c[i-1]),Math.abs(l[i]-c[i-1])));double cur=0;for(int i=1;i<=p;i++)cur+=tr[i];cur/=p;o[p]=cur;for(int i=p+1;i<c.length;i++){cur=(cur*(p-1)+tr[i])/p;o[i]=cur;}return o;}
    private static boolean finite(double...x){for(double v:x)if(!Double.isFinite(v))return false;return true;}
    private static double min(double[]a,int f,int t){f=Math.max(0,f);t=Math.min(a.length-1,t);double x=Double.POSITIVE_INFINITY;for(int i=f;i<=t;i++)x=Math.min(x,a[i]);return x;}
    private static double max(double[]a,int f,int t){f=Math.max(0,f);t=Math.min(a.length-1,t);double x=Double.NEGATIVE_INFINITY;for(int i=f;i<=t;i++)x=Math.max(x,a[i]);return x;}
    private static String fmt(double x){return Double.isFinite(x)?String.format(Locale.US,"%.2f",x):"—";}
}

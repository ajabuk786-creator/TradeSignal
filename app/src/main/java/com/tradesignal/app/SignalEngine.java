package com.tradesignal.app;

import java.util.*;

public final class SignalEngine {
    private SignalEngine() {}

    public static final class Candle {
        public final long openTime, closeTime;
        public final double open, high, low, close, volume;
        public Candle(long openTime, double open, double high, double low, double close, double volume, long closeTime) {
            this.openTime = openTime; this.open = open; this.high = high; this.low = low;
            this.close = close; this.volume = volume; this.closeTime = closeTime;
        }
    }

    public static final class Signal {
        public final String side, rationale, confirmationLabel, amdState;
        public final double entry, stopLoss, takeProfit, riskPerUnit, qtyAt2Pct, trailingTrigger, riskReward;
        public final int confidence, setupConfidence, triggerConfidence, votesFor, votesAgainst;
        public final long candleCloseTime;

        public Signal(String side, double entry, double stopLoss, double takeProfit, double riskPerUnit,
                      double qtyAt2Pct, double trailingTrigger, double riskReward,
                      int confidence, int setupConfidence, int triggerConfidence,
                      int votesFor, int votesAgainst, String confirmationLabel, String amdState,
                      String rationale, long candleCloseTime) {
            this.side = side; this.entry = entry; this.stopLoss = stopLoss; this.takeProfit = takeProfit;
            this.riskPerUnit = riskPerUnit; this.qtyAt2Pct = qtyAt2Pct; this.trailingTrigger = trailingTrigger;
            this.riskReward = riskReward; this.confidence = confidence; this.setupConfidence = setupConfidence;
            this.triggerConfidence = triggerConfidence; this.votesFor = votesFor; this.votesAgainst = votesAgainst;
            this.confirmationLabel = confirmationLabel; this.amdState = amdState;
            this.rationale = rationale; this.candleCloseTime = candleCloseTime;
        }
    }

    public static final class Decision {
        public final Signal signal;
        public final String direction, summary, amdState;
        public final int setupConfidence, triggerConfidence;
        public Decision(Signal signal, String direction, int setupConfidence, int triggerConfidence,
                        String amdState, String summary) {
            this.signal = signal; this.direction = direction; this.setupConfidence = setupConfidence;
            this.triggerConfidence = triggerConfidence; this.amdState = amdState; this.summary = summary;
        }
    }

    /**
     * Scalp pipeline:
     * 1h = context, 15m = setup, 5m = actual confirmation/entry trigger.
     * A strong 5m confirmation can qualify with a moderate 15m/1h setup, but a trade still
     * requires a structural trigger (breakout, retest, pullback continuation, squeeze, or AMD).
     */
    public static Decision analyzeScalp(List<Candle> m5, List<Candle> m15, List<Candle> h1,
                                        double paperEquity, int finalThresholdPct) {
        if (m5 == null || m15 == null || h1 == null || m5.size() < 120 || m15.size() < 90 || h1.size() < 80) {
            return new Decision(null, "NEUTRAL", 0, 0, "AMD unavailable", "Not enough closed candles yet.");
        }

        int i5 = m5.size()-1, p5 = i5-1;
        int i15 = m15.size()-1, p15 = i15-1;
        int ih = h1.size()-1;

        double[] c5=series(m5,'c'), o5=series(m5,'o'), hi5=series(m5,'h'), lo5=series(m5,'l'), v5=series(m5,'v');
        double[] c15=series(m15,'c'), hi15=series(m15,'h'), lo15=series(m15,'l'), v15=series(m15,'v');
        double[] ch=series(h1,'c');

        double[] e9_5=ema(c5,9), e20_5=ema(c5,20), rsi5=rsi(c5,14), atr5=atr(hi5,lo5,c5,14), vma5=sma(v5,20);
        double[] bbMid5=sma(c5,20), bbStd5=rollingStd(c5,20), cmf5=cmf(hi5,lo5,c5,v5,20), obv5=obv(c5,v5);

        double[] e20_15=ema(c15,20), e50_15=ema(c15,50), rsi15=rsi(c15,14), atr15=atr(hi15,lo15,c15,14), vma15=sma(v15,20);
        double[] bbMid15=sma(c15,20), cmf15=cmf(hi15,lo15,c15,v15,20), obv15=obv(c15,v15);
        double[] e20h=ema(ch,20), e50h=ema(ch,50);

        if (!finite(e9_5[i5],e20_5[i5],rsi5[i5],atr5[i5],vma5[i5],bbMid5[i5],bbStd5[i5],cmf5[i5],obv5[i5],
                e20_15[i15],e50_15[i15],rsi15[i15],atr15[i15],vma15[i15],bbMid15[i15],cmf15[i15],obv15[i15],e20h[ih],e50h[ih])) {
            return new Decision(null,"NEUTRAL",0,0,"AMD unavailable","Indicators are still warming up.");
        }

        double atrPct15 = atr15[i15] / Math.max(c15[i15],1e-12);
        double recentLow15=min(lo15,Math.max(0,i15-8),i15-1), priorLow15=min(lo15,Math.max(0,i15-16),Math.max(0,i15-9));
        double recentHigh15=max(hi15,Math.max(0,i15-8),i15-1), priorHigh15=max(hi15,Math.max(0,i15-16),Math.max(0,i15-9));

        boolean[] setupBuy = new boolean[] {
                e20_15[i15] > e50_15[i15], c15[i15] > e20_15[i15], e20_15[i15] >= e20_15[Math.max(0,i15-3)],
                e20h[ih] >= e50h[ih], ch[ih] >= e20h[ih], rsi15[i15] >= 47 && rsi15[i15] < 74,
                rsi15[i15] >= rsi15[p15]-2, c15[i15] >= bbMid15[i15], v15[i15] >= 0.80*vma15[i15],
                cmf15[i15] >= -0.03, obv15[i15] >= obv15[Math.max(0,i15-3)],
                recentLow15 >= priorLow15-0.20*atr15[i15] && atrPct15 > 0.00035 && atrPct15 < 0.08
        };
        boolean[] setupSell = new boolean[] {
                e20_15[i15] < e50_15[i15], c15[i15] < e20_15[i15], e20_15[i15] <= e20_15[Math.max(0,i15-3)],
                e20h[ih] <= e50h[ih], ch[ih] <= e20h[ih], rsi15[i15] <= 53 && rsi15[i15] > 26,
                rsi15[i15] <= rsi15[p15]+2, c15[i15] <= bbMid15[i15], v15[i15] >= 0.80*vma15[i15],
                cmf15[i15] <= 0.03, obv15[i15] <= obv15[Math.max(0,i15-3)],
                recentHigh15 <= priorHigh15+0.20*atr15[i15] && atrPct15 > 0.00035 && atrPct15 < 0.08
        };

        int buySetup=count(setupBuy), sellSetup=count(setupSell);
        String direction;
        int setupVotes, setupAgainst;
        if (buySetup >= sellSetup + 1 && buySetup >= 7) { direction="BUY"; setupVotes=buySetup; setupAgainst=sellSetup; }
        else if (sellSetup >= buySetup + 1 && sellSetup >= 7) { direction="SELL"; setupVotes=sellSetup; setupAgainst=buySetup; }
        else {
            int best=Math.max(buySetup,sellSetup);
            return new Decision(null,"NEUTRAL",pct(best,12),0,"No clean AMD direction",
                    "15m/1h context is mixed. Waiting for a clearer scalp bias.");
        }
        int setupConfidence=pct(setupVotes,12);
        if (setupConfidence < 58) {
            return new Decision(null,direction,setupConfidence,0,"No confirmed AMD sequence",
                    "Directional bias exists, but the 15m/1h setup is too weak for a scalp entry.");
        }

        double rangeHigh=max(hi5,Math.max(0,i5-22),Math.max(0,i5-7));
        double rangeLow=min(lo5,Math.max(0,i5-22),Math.max(0,i5-7));
        double rangeWidth=rangeHigh-rangeLow;
        boolean accumulation=rangeWidth <= 5.5*atr5[i5];
        boolean sweptLow=false, sweptHigh=false;
        for (int j=Math.max(1,i5-6); j<=i5-1; j++) {
            if (lo5[j] < rangeLow-0.04*atr5[i5] && c5[j] > rangeLow) sweptLow=true;
            if (hi5[j] > rangeHigh+0.04*atr5[i5] && c5[j] < rangeHigh) sweptHigh=true;
        }
        double microHigh5=max(hi5,Math.max(0,i5-5),i5-1), microLow5=min(lo5,Math.max(0,i5-5),i5-1);
        boolean amdLong=accumulation && sweptLow && c5[i5] > microHigh5 && c5[i5] > e9_5[i5];
        boolean amdShort=accumulation && sweptHigh && c5[i5] < microLow5 && c5[i5] < e9_5[i5];
        String amdState=amdLong ? "AMD LONG confirmed: accumulation → downside sweep → distribution"
                : amdShort ? "AMD SHORT confirmed: accumulation → upside sweep → distribution"
                : accumulation ? "AMD accumulation/range present; waiting for sweep + distribution"
                : "No clean AMD range; other confirmation logic used";

        double pivotHigh=max(hi5,Math.max(0,i5-6),i5-1), pivotLow=min(lo5,Math.max(0,i5-6),i5-1);
        double olderHigh=max(hi5,Math.max(0,i5-11),Math.max(0,i5-3)), olderLow=min(lo5,Math.max(0,i5-11),Math.max(0,i5-3));
        double micro3High=max(hi5,Math.max(0,i5-3),i5-1), micro3Low=min(lo5,Math.max(0,i5-3),i5-1);

        boolean volumeOkay=v5[i5] >= 0.85*vma5[i5];
        boolean breakoutLong=c5[i5] > pivotHigh+0.02*atr5[i5] && volumeOkay;
        boolean breakoutShort=c5[i5] < pivotLow-0.02*atr5[i5] && volumeOkay;
        boolean microBreakLong=c5[i5] > micro3High && c5[i5] > e9_5[i5] && volumeOkay;
        boolean microBreakShort=c5[i5] < micro3Low && c5[i5] < e9_5[i5] && volumeOkay;
        boolean retestLong=lo5[p5] <= olderHigh+0.18*atr5[i5] && c5[p5] >= olderHigh-0.08*atr5[i5] && c5[i5] > hi5[p5] && c5[i5] > olderHigh;
        boolean retestShort=hi5[p5] >= olderLow-0.18*atr5[i5] && c5[p5] <= olderLow+0.08*atr5[i5] && c5[i5] < lo5[p5] && c5[i5] < olderLow;

        double upper5=bbMid5[i5]+2.0*bbStd5[i5], lower5=bbMid5[i5]-2.0*bbStd5[i5];
        double prevWidth=4.0*bbStd5[p5]/Math.max(bbMid5[p5],1e-12), curWidth=4.0*bbStd5[i5]/Math.max(bbMid5[i5],1e-12);
        double widthAvg=averageBbWidth(bbMid5,bbStd5,Math.max(20,i5-20),i5-1);
        boolean squeeze=Double.isFinite(widthAvg) && prevWidth < 0.90*widthAvg && curWidth > prevWidth;
        boolean squeezeLong=squeeze && c5[i5] > upper5 && volumeOkay;
        boolean squeezeShort=squeeze && c5[i5] < lower5 && volumeOkay;

        boolean pullbackLong=e9_5[i5] > e20_5[i5] && lo5[p5] <= e20_5[p5]+0.20*atr5[i5]
                && c5[i5] > e9_5[i5] && c5[i5] > hi5[p5] && rsi5[i5] >= 50 && volumeOkay;
        boolean pullbackShort=e9_5[i5] < e20_5[i5] && hi5[p5] >= e20_5[p5]-0.20*atr5[i5]
                && c5[i5] < e9_5[i5] && c5[i5] < lo5[p5] && rsi5[i5] <= 50 && volumeOkay;

        double body=c5[i5]-o5[i5], candleRange=Math.max(hi5[i5]-lo5[i5],1e-12), bodyRatio=Math.abs(body)/candleRange;
        boolean notChasing=Math.abs(c5[i5]-e9_5[i5]) <= 1.25*atr5[i5];

        boolean structuralLong=breakoutLong||retestLong||amdLong||squeezeLong||pullbackLong||microBreakLong;
        boolean structuralShort=breakoutShort||retestShort||amdShort||squeezeShort||pullbackShort||microBreakShort;

        boolean[] triggerBuy=new boolean[] {
                e9_5[i5] > e20_5[i5], c5[i5] > e9_5[i5], rsi5[i5] >= 48 && rsi5[i5] <= 72,
                rsi5[i5] >= rsi5[p5]-1, volumeOkay, cmf5[i5] >= -0.02,
                obv5[i5] >= obv5[Math.max(0,i5-3)], structuralLong, body > 0 && bodyRatio >= 0.25, notChasing
        };
        boolean[] triggerSell=new boolean[] {
                e9_5[i5] < e20_5[i5], c5[i5] < e9_5[i5], rsi5[i5] <= 52 && rsi5[i5] >= 28,
                rsi5[i5] <= rsi5[p5]+1, volumeOkay, cmf5[i5] <= 0.02,
                obv5[i5] <= obv5[Math.max(0,i5-3)], structuralShort, body < 0 && bodyRatio >= 0.25, notChasing
        };

        int triggerVotes="BUY".equals(direction)?count(triggerBuy):count(triggerSell);
        int triggerConfidence=pct(triggerVotes,10);
        boolean hardTrigger="BUY".equals(direction)
                ? structuralLong && body>0 && volumeOkay && notChasing
                : structuralShort && body<0 && volumeOkay && notChasing;

        if (triggerConfidence < 70 || !hardTrigger) {
            return new Decision(null,direction,setupConfidence,triggerConfidence,amdState,
                    "Bias " + setupConfidence + "% · 5m confirmation " + triggerConfidence + "%. Waiting for a confirmed breakout/retest/pullback/AMD trigger.");
        }

        int finalConfidence=(int)Math.round(0.45*setupConfidence + 0.55*triggerConfidence);
        if (finalConfidence < finalThresholdPct) {
            return new Decision(null,direction,setupConfidence,triggerConfidence,amdState,
                    "A trigger exists, but combined confidence is " + finalConfidence + "%. No confirmed signal yet.");
        }

        double entry=c5[i5], atr=atr5[i5];
        double swingLow=min(lo5,Math.max(0,i5-5),i5)-0.08*atr;
        double swingHigh=max(hi5,Math.max(0,i5-5),i5)+0.08*atr;
        double stop;
        if ("BUY".equals(direction)) {
            stop=Math.max(entry-1.80*atr, Math.min(entry-1.05*atr, swingLow));
        } else {
            stop=Math.min(entry+1.80*atr, Math.max(entry+1.05*atr, swingHigh));
        }
        double risk=Math.abs(entry-stop);
        if (!Double.isFinite(risk) || risk <= 0) return new Decision(null,direction,setupConfidence,triggerConfidence,amdState,"Invalid volatility stop; signal rejected.");

        double rr=(amdLong||amdShort||triggerConfidence>=90)?1.50:(triggerConfidence>=80?1.40:1.30);
        double tp="BUY".equals(direction)?entry+rr*risk:entry-rr*risk;
        double trailTrigger="BUY".equals(direction)?entry+0.80*risk:entry-0.80*risk;
        double riskBudget=Math.max(0.0,paperEquity)*0.02;
        double qty=riskBudget/risk;

        String confirmation;
        if (amdLong||amdShort) confirmation="AMD sweep + distribution";
        else if (("BUY".equals(direction)&&retestLong)||("SELL".equals(direction)&&retestShort)) confirmation="Breakout retest confirmation";
        else if (("BUY".equals(direction)&&pullbackLong)||("SELL".equals(direction)&&pullbackShort)) confirmation="EMA pullback continuation";
        else if (("BUY".equals(direction)&&squeezeLong)||("SELL".equals(direction)&&squeezeShort)) confirmation="Bollinger squeeze expansion";
        else if (("BUY".equals(direction)&&breakoutLong)||("SELL".equals(direction)&&breakoutShort)) confirmation="Volume-confirmed breakout";
        else confirmation="Micro-structure break + momentum";

        String rationale="15m/1h setup " + setupConfidence + "% + 5m trigger " + triggerConfidence + "% · " + confirmation + ".";
        Signal s=new Signal(direction,entry,stop,tp,risk,qty,trailTrigger,rr,finalConfidence,setupConfidence,triggerConfidence,
                triggerVotes,10-triggerVotes,confirmation,amdState,rationale,m5.get(i5).closeTime);
        return new Decision(s,direction,setupConfidence,triggerConfidence,amdState,
                "CONFIRMED " + direction + " scalp · " + finalConfidence + "% · " + confirmation + ". Saved as a signal; it is not active until you choose TAKE.");
    }

    private static int count(boolean[] a){int n=0;for(boolean x:a)if(x)n++;return n;}
    private static int pct(int n,int d){return (int)Math.round(n*100.0/d);}
    private static boolean finite(double... xs){for(double x:xs)if(!Double.isFinite(x))return false;return true;}

    private static double[] series(List<Candle> c,char field){
        double[] out=new double[c.size()];
        for(int i=0;i<c.size();i++){
            Candle x=c.get(i);
            switch(field){case'o':out[i]=x.open;break;case'h':out[i]=x.high;break;case'l':out[i]=x.low;break;case'v':out[i]=x.volume;break;default:out[i]=x.close;}
        }
        return out;
    }

    private static double[] ema(double[] v,int p){
        double[] out=new double[v.length];Arrays.fill(out,Double.NaN);if(v.length<p)return out;
        double sum=0;for(int i=0;i<p;i++)sum+=v[i];double cur=sum/p;out[p-1]=cur;double k=2.0/(p+1.0);
        for(int i=p;i<v.length;i++){cur=(v[i]-cur)*k+cur;out[i]=cur;}return out;
    }
    private static double[] sma(double[] v,int p){
        double[] out=new double[v.length];Arrays.fill(out,Double.NaN);double sum=0;
        for(int i=0;i<v.length;i++){sum+=v[i];if(i>=p)sum-=v[i-p];if(i>=p-1)out[i]=sum/p;}return out;
    }
    private static double[] rollingStd(double[] v,int p){
        double[] out=new double[v.length];Arrays.fill(out,Double.NaN);
        for(int i=p-1;i<v.length;i++){double m=0;for(int j=i-p+1;j<=i;j++)m+=v[j];m/=p;double s=0;for(int j=i-p+1;j<=i;j++){double d=v[j]-m;s+=d*d;}out[i]=Math.sqrt(s/p);}return out;
    }
    private static double[] rsi(double[] v,int p){
        double[] out=new double[v.length];Arrays.fill(out,Double.NaN);if(v.length<=p)return out;
        double g=0,l=0;for(int i=1;i<=p;i++){double d=v[i]-v[i-1];g+=Math.max(d,0);l+=Math.max(-d,0);}g/=p;l/=p;out[p]=l==0?100:100-100/(1+g/l);
        for(int i=p+1;i<v.length;i++){double d=v[i]-v[i-1];g=(g*(p-1)+Math.max(d,0))/p;l=(l*(p-1)+Math.max(-d,0))/p;out[i]=l==0?100:100-100/(1+g/l);}return out;
    }
    private static double[] atr(double[] h,double[] l,double[] c,int p){
        double[] out=new double[c.length];Arrays.fill(out,Double.NaN);if(c.length<=p)return out;double[] tr=new double[c.length];tr[0]=h[0]-l[0];
        for(int i=1;i<c.length;i++)tr[i]=Math.max(h[i]-l[i],Math.max(Math.abs(h[i]-c[i-1]),Math.abs(l[i]-c[i-1])));
        double cur=0;for(int i=1;i<=p;i++)cur+=tr[i];cur/=p;out[p]=cur;for(int i=p+1;i<c.length;i++){cur=(cur*(p-1)+tr[i])/p;out[i]=cur;}return out;
    }
    private static double[] obv(double[] c,double[] v){
        double[] out=new double[c.length];if(c.length==0)return out;out[0]=v[0];
        for(int i=1;i<c.length;i++){out[i]=out[i-1]+(c[i]>c[i-1]?v[i]:c[i]<c[i-1]?-v[i]:0);}return out;
    }
    private static double[] cmf(double[] h,double[] l,double[] c,double[] v,int p){
        double[] out=new double[c.length];Arrays.fill(out,Double.NaN);double[] mfv=new double[c.length];
        for(int i=0;i<c.length;i++){double r=h[i]-l[i];double m=r==0?0:((c[i]-l[i])-(h[i]-c[i]))/r;mfv[i]=m*v[i];}
        double mSum=0,vSum=0;for(int i=0;i<c.length;i++){mSum+=mfv[i];vSum+=v[i];if(i>=p){mSum-=mfv[i-p];vSum-=v[i-p];}if(i>=p-1)out[i]=vSum==0?0:mSum/vSum;}return out;
    }
    private static double averageBbWidth(double[] mid,double[] std,int from,int to){
        from=Math.max(0,from);to=Math.min(mid.length-1,to);double sum=0;int n=0;
        for(int i=from;i<=to;i++){if(Double.isFinite(mid[i])&&Double.isFinite(std[i])&&Math.abs(mid[i])>1e-12){sum+=4.0*std[i]/Math.abs(mid[i]);n++;}}return n==0?Double.NaN:sum/n;
    }
    private static double min(double[] a,int from,int to){from=Math.max(0,from);to=Math.min(a.length-1,to);double x=Double.POSITIVE_INFINITY;for(int i=from;i<=to;i++)x=Math.min(x,a[i]);return x;}
    private static double max(double[] a,int from,int to){from=Math.max(0,from);to=Math.min(a.length-1,to);double x=Double.NEGATIVE_INFINITY;for(int i=from;i<=to;i++)x=Math.max(x,a[i]);return x;}
}

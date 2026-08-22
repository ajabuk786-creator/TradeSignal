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
     * SCALP PIPELINE
     * Stage 1: 15m + 1h context must establish directional bias.
     * Stage 2: a CLOSED 5m candle must confirm entry with breakout/retest, AMD sweep/distribution,
     *          or squeeze expansion plus momentum/volume confirmation.
     * A setup is not a trade until Stage 2 confirms.
     */
    public static Decision analyzeScalp(List<Candle> m5, List<Candle> m15, List<Candle> h1,
                                        double paperEquity, int finalThresholdPct) {
        if (m5 == null || m15 == null || h1 == null || m5.size() < 100 || m15.size() < 80 || h1.size() < 80)
            return new Decision(null, "NEUTRAL", 0, 0, "AMD unavailable", "Not enough closed candles yet.");

        int i5 = m5.size() - 1, p5 = i5 - 1;
        int i15 = m15.size() - 1, p15 = i15 - 1;
        int ih = h1.size() - 1;

        double[] c5 = series(m5,'c'), o5 = series(m5,'o'), hi5 = series(m5,'h'), lo5 = series(m5,'l'), v5 = series(m5,'v');
        double[] c15 = series(m15,'c'), hi15 = series(m15,'h'), lo15 = series(m15,'l'), v15 = series(m15,'v');
        double[] ch = series(h1,'c');

        double[] e9_5 = ema(c5,9), e20_5 = ema(c5,20), rsi5 = rsi(c5,14), atr5 = atr(hi5,lo5,c5,14), vma5 = sma(v5,20);
        double[] bbMid5 = sma(c5,20), bbStd5 = rollingStd(c5,20), cmf5 = cmf(hi5,lo5,c5,v5,20), obv5 = obv(c5,v5);

        double[] e20_15 = ema(c15,20), e50_15 = ema(c15,50), rsi15 = rsi(c15,14), atr15 = atr(hi15,lo15,c15,14), vma15 = sma(v15,20);
        double[] bbMid15 = sma(c15,20), cmf15 = cmf(hi15,lo15,c15,v15,20), obv15 = obv(c15,v15);
        double[] e20h = ema(ch,20), e50h = ema(ch,50);

        if (!finite(e9_5[i5],e20_5[i5],rsi5[i5],atr5[i5],vma5[i5],bbMid5[i5],bbStd5[i5],cmf5[i5],obv5[i5],
                e20_15[i15],e50_15[i15],rsi15[i15],atr15[i15],vma15[i15],bbMid15[i15],cmf15[i15],obv15[i15],e20h[ih],e50h[ih])) {
            return new Decision(null,"NEUTRAL",0,0,"AMD unavailable","Indicators are still warming up.");
        }

        // ---------- STAGE 1: 15m + 1h directional setup (12 checks) ----------
        double atrPct15 = atr15[i15] / Math.max(c15[i15], 1e-12);
        double recentLow15 = min(lo15, Math.max(0,i15-8), i15-1);
        double priorLow15 = min(lo15, Math.max(0,i15-16), Math.max(0,i15-9));
        double recentHigh15 = max(hi15, Math.max(0,i15-8), i15-1);
        double priorHigh15 = max(hi15, Math.max(0,i15-16), Math.max(0,i15-9));

        boolean[] setupBuy = new boolean[] {
                e20_15[i15] > e50_15[i15],
                c15[i15] > e20_15[i15],
                e20_15[i15] > e20_15[Math.max(0,i15-3)],
                e20h[ih] > e50h[ih],
                ch[ih] > e20h[ih],
                rsi15[i15] >= 50 && rsi15[i15] < 72,
                rsi15[i15] >= rsi15[p15],
                c15[i15] > bbMid15[i15],
                v15[i15] >= 0.90 * vma15[i15],
                cmf15[i15] >= 0,
                obv15[i15] >= obv15[Math.max(0,i15-3)],
                recentLow15 >= priorLow15 && atrPct15 > 0.0004 && atrPct15 < 0.08
        };
        boolean[] setupSell = new boolean[] {
                e20_15[i15] < e50_15[i15],
                c15[i15] < e20_15[i15],
                e20_15[i15] < e20_15[Math.max(0,i15-3)],
                e20h[ih] < e50h[ih],
                ch[ih] < e20h[ih],
                rsi15[i15] <= 50 && rsi15[i15] > 28,
                rsi15[i15] <= rsi15[p15],
                c15[i15] < bbMid15[i15],
                v15[i15] >= 0.90 * vma15[i15],
                cmf15[i15] <= 0,
                obv15[i15] <= obv15[Math.max(0,i15-3)],
                recentHigh15 <= priorHigh15 && atrPct15 > 0.0004 && atrPct15 < 0.08
        };

        int setupBuyVotes = count(setupBuy), setupSellVotes = count(setupSell);
        String direction;
        int setupVotes, setupAgainst;
        if (setupBuyVotes >= setupSellVotes + 2) {
            direction = "BUY"; setupVotes = setupBuyVotes; setupAgainst = setupSellVotes;
        } else if (setupSellVotes >= setupBuyVotes + 2) {
            direction = "SELL"; setupVotes = setupSellVotes; setupAgainst = setupBuyVotes;
        } else {
            int best = Math.max(setupBuyVotes, setupSellVotes);
            return new Decision(null,"NEUTRAL",pct(best,12),0,"No clean AMD direction",
                    "15m/1h context is mixed. Waiting for a cleaner directional setup.");
        }
        int setupConfidence = pct(setupVotes,12);
        if (setupConfidence < 67) {
            return new Decision(null,direction,setupConfidence,0,"No confirmed AMD sequence",
                    "Directional idea exists, but 15m/1h setup is only " + setupConfidence + "%. Waiting.");
        }

        // ---------- AMD approximation on 5m: accumulation range -> liquidity sweep -> distribution ----------
        double rangeHigh = max(hi5, Math.max(0,i5-20), Math.max(0,i5-6));
        double rangeLow = min(lo5, Math.max(0,i5-20), Math.max(0,i5-6));
        double rangeWidth = rangeHigh - rangeLow;
        boolean accumulation = rangeWidth <= 5.0 * atr5[i5];
        boolean sweptLow = false, sweptHigh = false;
        for (int j=Math.max(1,i5-5); j<=i5-1; j++) {
            if (lo5[j] < rangeLow - 0.05*atr5[i5] && c5[j] > rangeLow) sweptLow = true;
            if (hi5[j] > rangeHigh + 0.05*atr5[i5] && c5[j] < rangeHigh) sweptHigh = true;
        }
        double microHigh = max(hi5, Math.max(0,i5-5), i5-1);
        double microLow = min(lo5, Math.max(0,i5-5), i5-1);
        boolean amdLong = accumulation && sweptLow && c5[i5] > microHigh && c5[i5] > e9_5[i5];
        boolean amdShort = accumulation && sweptHigh && c5[i5] < microLow && c5[i5] < e9_5[i5];
        String amdState = amdLong ? "AMD LONG: accumulation → downside sweep → bullish distribution"
                : amdShort ? "AMD SHORT: accumulation → upside sweep → bearish distribution"
                : accumulation ? "AMD accumulation/range detected; waiting for manipulation + distribution"
                : "No clean AMD range; breakout logic used";

        // ---------- STAGE 2: 5m trigger and entry confirmation ----------
        double pivotHigh = max(hi5, Math.max(0,i5-7), i5-1);
        double pivotLow = min(lo5, Math.max(0,i5-7), i5-1);
        double olderHigh = max(hi5, Math.max(0,i5-10), Math.max(0,i5-3));
        double olderLow = min(lo5, Math.max(0,i5-10), Math.max(0,i5-3));

        boolean breakoutLong = c5[i5] > pivotHigh + 0.04*atr5[i5] && v5[i5] >= vma5[i5];
        boolean breakoutShort = c5[i5] < pivotLow - 0.04*atr5[i5] && v5[i5] >= vma5[i5];
        boolean retestLong = lo5[p5] <= olderHigh + 0.15*atr5[i5] && c5[p5] >= olderHigh - 0.05*atr5[i5]
                && c5[i5] > hi5[p5] && c5[i5] > olderHigh;
        boolean retestShort = hi5[p5] >= olderLow - 0.15*atr5[i5] && c5[p5] <= olderLow + 0.05*atr5[i5]
                && c5[i5] < lo5[p5] && c5[i5] < olderLow;

        double upper5 = bbMid5[i5] + 2.0*bbStd5[i5], lower5 = bbMid5[i5] - 2.0*bbStd5[i5];
        double prevWidth = (bbMid5[p5] + 2.0*bbStd5[p5] - (bbMid5[p5] - 2.0*bbStd5[p5])) / Math.max(bbMid5[p5],1e-12);
        double curWidth = (upper5-lower5) / Math.max(bbMid5[i5],1e-12);
        double widthAvg = averageBbWidth(bbMid5,bbStd5,Math.max(20,i5-20),i5-1);
        boolean squeeze = Double.isFinite(widthAvg) && prevWidth < 0.85*widthAvg && curWidth > prevWidth;
        boolean squeezeLong = squeeze && c5[i5] > upper5;
        boolean squeezeShort = squeeze && c5[i5] < lower5;

        double body = c5[i5]-o5[i5];
        double candleRange = Math.max(hi5[i5]-lo5[i5],1e-12);
        double bodyRatio = Math.abs(body)/candleRange;
        boolean notChasing = Math.abs(c5[i5]-e9_5[i5]) <= 1.20*atr5[i5];
        boolean volumeOkay = v5[i5] >= 0.90*vma5[i5];

        boolean structuralLongTrigger = breakoutLong || retestLong || amdLong || squeezeLong;
        boolean structuralShortTrigger = breakoutShort || retestShort || amdShort || squeezeShort;

        boolean[] triggerBuy = new boolean[] {
                e9_5[i5] > e20_5[i5],
                c5[i5] > e9_5[i5],
                rsi5[i5] >= 50 && rsi5[i5] <= 70,
                rsi5[i5] > rsi5[p5],
                volumeOkay,
                cmf5[i5] > 0,
                obv5[i5] > obv5[Math.max(0,i5-3)],
                structuralLongTrigger,
                body > 0 && bodyRatio >= 0.30,
                notChasing
        };
        boolean[] triggerSell = new boolean[] {
                e9_5[i5] < e20_5[i5],
                c5[i5] < e9_5[i5],
                rsi5[i5] <= 50 && rsi5[i5] >= 30,
                rsi5[i5] < rsi5[p5],
                volumeOkay,
                cmf5[i5] < 0,
                obv5[i5] < obv5[Math.max(0,i5-3)],
                structuralShortTrigger,
                body < 0 && bodyRatio >= 0.30,
                notChasing
        };

        int triggerVotes = "BUY".equals(direction) ? count(triggerBuy) : count(triggerSell);
        int triggerAgainst = 10-triggerVotes;
        int triggerConfidence = pct(triggerVotes,10);
        boolean hardTrigger = "BUY".equals(direction)
                ? structuralLongTrigger && body > 0 && volumeOkay && notChasing
                : structuralShortTrigger && body < 0 && volumeOkay && notChasing;

        if (triggerConfidence < 70 || !hardTrigger) {
            String why = "15m/1h " + direction + " setup " + setupConfidence + "% · 5m trigger " + triggerConfidence
                    + "%. Waiting for confirmed breakout/retest or AMD distribution; no entry yet.";
            return new Decision(null,direction,setupConfidence,triggerConfidence,amdState,why);
        }

        int finalConfidence = (int)Math.round(0.60*setupConfidence + 0.40*triggerConfidence);
        if (finalConfidence < finalThresholdPct) {
            return new Decision(null,direction,setupConfidence,triggerConfidence,amdState,
                    "Setup and trigger exist, but combined confirmation is only " + finalConfidence + "%. Waiting.");
        }

        // ---------- Scalp risk/target: small logical stop, modest target, no all-day hold ----------
        double entry = c5[i5];
        double recentSwingLow = min(lo5,Math.max(0,i5-6),i5);
        double recentSwingHigh = max(hi5,Math.max(0,i5-6),i5);
        double rawRisk = "BUY".equals(direction)
                ? entry - (recentSwingLow - 0.08*atr5[i5])
                : (recentSwingHigh + 0.08*atr5[i5]) - entry;
        double risk = clamp(rawRisk,0.80*atr5[i5],1.35*atr5[i5]);
        if (!Double.isFinite(risk) || risk <= 0) {
            return new Decision(null,direction,setupConfidence,triggerConfidence,amdState,"Risk geometry is invalid; trade skipped.");
        }

        double stop = "BUY".equals(direction) ? entry-risk : entry+risk;
        double rr = triggerConfidence >= 90 ? 1.50 : triggerConfidence >= 80 ? 1.40 : 1.30;
        double tp = "BUY".equals(direction) ? entry + rr*risk : entry - rr*risk;
        double trailTrigger = "BUY".equals(direction) ? entry + 0.80*risk : entry - 0.80*risk;
        double riskBudget = Math.max(0,paperEquity)*0.02;
        double qty = riskBudget/risk;

        String label;
        if ("BUY".equals(direction) && amdLong || "SELL".equals(direction) && amdShort) label = "AMD sweep + 5m distribution breakout";
        else if ("BUY".equals(direction) && retestLong || "SELL".equals(direction) && retestShort) label = "5m breakout-retest confirmation";
        else if ("BUY".equals(direction) && squeezeLong || "SELL".equals(direction) && squeezeShort) label = "5m Bollinger squeeze breakout";
        else label = "5m breakout + volume confirmation";

        String rationale = "Confirmed scalp: 15m/1h setup " + setupConfidence + "% + 5m trigger " + triggerConfidence
                + "% · " + label + " · CMF/OBV/volume aligned · max hold 60m.";
        Signal s = new Signal(direction,entry,stop,tp,risk,qty,trailTrigger,rr,finalConfidence,
                setupConfidence,triggerConfidence,setupVotes,setupAgainst,label,amdState,rationale,m5.get(i5).closeTime);
        return new Decision(s,direction,setupConfidence,triggerConfidence,amdState,rationale);
    }

    private static int count(boolean[] a) { int n=0; for(boolean x:a) if(x) n++; return n; }
    private static int pct(int votes,int total) { return (int)Math.round(votes*100.0/Math.max(1,total)); }
    private static double clamp(double x,double lo,double hi) { return Math.max(lo,Math.min(hi,x)); }
    private static boolean finite(double... xs) { for(double x:xs) if(!Double.isFinite(x)) return false; return true; }

    private static double[] series(List<Candle> c,char field) {
        double[] out=new double[c.size()];
        for(int i=0;i<c.size();i++) {
            Candle x=c.get(i);
            switch(field) {
                case 'o': out[i]=x.open; break;
                case 'h': out[i]=x.high; break;
                case 'l': out[i]=x.low; break;
                case 'v': out[i]=x.volume; break;
                default: out[i]=x.close;
            }
        }
        return out;
    }

    private static double[] ema(double[] v,int p) {
        double[] out=new double[v.length]; Arrays.fill(out,Double.NaN); if(v.length<p) return out;
        double sum=0; for(int i=0;i<p;i++) sum+=v[i]; double cur=sum/p; out[p-1]=cur; double k=2.0/(p+1.0);
        for(int i=p;i<v.length;i++) { cur=(v[i]-cur)*k+cur; out[i]=cur; } return out;
    }
    private static double[] sma(double[] v,int p) {
        double[] out=new double[v.length]; Arrays.fill(out,Double.NaN); if(v.length<p) return out;
        double sum=0; for(int i=0;i<v.length;i++) { sum+=v[i]; if(i>=p) sum-=v[i-p]; if(i>=p-1) out[i]=sum/p; } return out;
    }
    private static double[] rollingStd(double[] v,int p) {
        double[] out=new double[v.length]; Arrays.fill(out,Double.NaN); if(v.length<p) return out;
        for(int i=p-1;i<v.length;i++) { double m=0; for(int j=i-p+1;j<=i;j++) m+=v[j]; m/=p; double s=0; for(int j=i-p+1;j<=i;j++){double d=v[j]-m;s+=d*d;} out[i]=Math.sqrt(s/p); } return out;
    }
    private static double[] rsi(double[] v,int p) {
        double[] out=new double[v.length]; Arrays.fill(out,Double.NaN); if(v.length<=p) return out;
        double g=0,l=0; for(int i=1;i<=p;i++){double d=v[i]-v[i-1];g+=Math.max(d,0);l+=Math.max(-d,0);} g/=p;l/=p;out[p]=l==0?100:100-100/(1+g/l);
        for(int i=p+1;i<v.length;i++){double d=v[i]-v[i-1];g=(g*(p-1)+Math.max(d,0))/p;l=(l*(p-1)+Math.max(-d,0))/p;out[i]=l==0?100:100-100/(1+g/l);} return out;
    }
    private static double[] atr(double[] h,double[] l,double[] c,int p) {
        double[] out=new double[c.length]; Arrays.fill(out,Double.NaN); if(c.length<=p) return out;
        double[] tr=new double[c.length]; tr[0]=h[0]-l[0]; for(int i=1;i<c.length;i++) tr[i]=Math.max(h[i]-l[i],Math.max(Math.abs(h[i]-c[i-1]),Math.abs(l[i]-c[i-1])));
        double cur=0; for(int i=1;i<=p;i++) cur+=tr[i]; cur/=p; out[p]=cur; for(int i=p+1;i<c.length;i++){cur=(cur*(p-1)+tr[i])/p;out[i]=cur;} return out;
    }
    private static double[] cmf(double[] h,double[] l,double[] c,double[] v,int p) {
        double[] out=new double[c.length]; Arrays.fill(out,Double.NaN); double mfvSum=0,volSum=0; double[] mfv=new double[c.length];
        for(int i=0;i<c.length;i++){double range=h[i]-l[i]; double mult=range==0?0:((c[i]-l[i])-(h[i]-c[i]))/range; mfv[i]=mult*v[i]; mfvSum+=mfv[i]; volSum+=v[i]; if(i>=p){mfvSum-=mfv[i-p];volSum-=v[i-p];} if(i>=p-1) out[i]=volSum==0?0:mfvSum/volSum;} return out;
    }
    private static double[] obv(double[] c,double[] v) {
        double[] out=new double[c.length]; if(c.length==0) return out; out[0]=0; for(int i=1;i<c.length;i++){out[i]=out[i-1]+(c[i]>c[i-1]?v[i]:c[i]<c[i-1]?-v[i]:0);} return out;
    }
    private static double averageBbWidth(double[] mid,double[] std,int from,int to) {
        from=Math.max(0,from);to=Math.min(mid.length-1,to);double sum=0;int n=0;for(int i=from;i<=to;i++){if(Double.isFinite(mid[i])&&Double.isFinite(std[i])&&mid[i]!=0){sum+=(4.0*std[i])/Math.abs(mid[i]);n++;}}return n==0?Double.NaN:sum/n;
    }
    private static double min(double[] a,int from,int to) { from=Math.max(0,from);to=Math.min(a.length-1,to);double x=Double.POSITIVE_INFINITY;for(int i=from;i<=to;i++)x=Math.min(x,a[i]);return x; }
    private static double max(double[] a,int from,int to) { from=Math.max(0,from);to=Math.min(a.length-1,to);double x=Double.NEGATIVE_INFINITY;for(int i=from;i<=to;i++)x=Math.max(x,a[i]);return x; }
}

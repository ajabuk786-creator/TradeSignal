package com.tradesignal.app;

import java.util.*;

/**
 * TradeSignal v5 strategy engine.
 *
 * Design goals:
 * - stop treating many correlated indicators as independent "votes"
 * - generate a signal only when ONE complete setup is present
 * - use 1h as context, 15m as structure/setup, and 5m as the entry trigger
 * - separate TREND_PULLBACK, BREAKOUT_RETEST and AMD_REVERSAL logic
 * - use structural invalidation for SL and nearby liquidity/structure for TP
 * - expose a rule-quality score (1..10), not a claimed probability of winning
 */
public final class SignalEngine {
    private SignalEngine() {}

    public static final class Candle {
        public final long openTime, closeTime;
        public final double open, high, low, close, volume;
        public Candle(long openTime,double open,double high,double low,double close,double volume,long closeTime){
            this.openTime=openTime;this.open=open;this.high=high;this.low=low;this.close=close;this.volume=volume;this.closeTime=closeTime;
        }
    }

    public static final class Signal {
        public final String side,rationale,confirmationLabel,amdState,structureKey,regime;
        public final double entry,stopLoss,takeProfit,riskPerUnit,qtyAt2Pct,trailingTrigger,riskReward;
        // confidence is kept only for backwards storage compatibility. It equals qualityScore*10.
        public final int confidence,setupConfidence,triggerConfidence,qualityScore,votesFor,votesAgainst;
        public final long candleCloseTime;
        public Signal(String side,double entry,double stopLoss,double takeProfit,double riskPerUnit,
                      double qtyAt2Pct,double trailingTrigger,double riskReward,int qualityScore,
                      int setupConfidence,int triggerConfidence,String confirmationLabel,String amdState,
                      String structureKey,String regime,String rationale,long candleCloseTime){
            this.side=side;this.entry=entry;this.stopLoss=stopLoss;this.takeProfit=takeProfit;
            this.riskPerUnit=riskPerUnit;this.qtyAt2Pct=qtyAt2Pct;this.trailingTrigger=trailingTrigger;
            this.riskReward=riskReward;this.qualityScore=qualityScore;this.confidence=qualityScore*10;
            this.setupConfidence=setupConfidence;this.triggerConfidence=triggerConfidence;
            this.votesFor=qualityScore;this.votesAgainst=10-qualityScore;
            this.confirmationLabel=confirmationLabel;this.amdState=amdState;this.structureKey=structureKey;
            this.regime=regime;this.rationale=rationale;this.candleCloseTime=candleCloseTime;
        }
    }

    public static final class Decision {
        public final Signal signal;
        public final String direction,summary,amdState,regime;
        public final int setupConfidence,triggerConfidence,qualityScore;
        public Decision(Signal signal,String direction,int setupConfidence,int triggerConfidence,int qualityScore,
                        String amdState,String regime,String summary){
            this.signal=signal;this.direction=direction;this.setupConfidence=setupConfidence;
            this.triggerConfidence=triggerConfidence;this.qualityScore=qualityScore;this.amdState=amdState;
            this.regime=regime;this.summary=summary;
        }
    }

    private static final class Candidate {
        String side,type,amdState,structureKey,regime,rationale;
        int quality,setupScore,triggerScore;
        double entry,sl,targetHint;
        long time;
    }

    public static Decision analyzeScalp(List<Candle> m5,List<Candle> m15,List<Candle> h1,double paperEquity,int ignoredThreshold){
        if(m5==null||m15==null||h1==null||m5.size()<140||m15.size()<100||h1.size()<80)
            return new Decision(null,"NEUTRAL",0,0,0,"AMD unavailable","WARMUP","Not enough closed candles for v5 structure analysis.");

        int i5=m5.size()-1,p5=i5-1;
        int i15=m15.size()-1,p15=i15-1;
        int ih=h1.size()-1;

        double[] c5=series(m5,'c'),o5=series(m5,'o'),hi5=series(m5,'h'),lo5=series(m5,'l'),v5=series(m5,'v');
        double[] c15=series(m15,'c'),hi15=series(m15,'h'),lo15=series(m15,'l'),v15=series(m15,'v');
        double[] ch=series(h1,'c');

        double[] e9_5=ema(c5,9),e20_5=ema(c5,20),rsi5=rsi(c5,14),atr5=atr(hi5,lo5,c5,14),vma5=sma(v5,20),cmf5=cmf(hi5,lo5,c5,v5,20),obv5=obv(c5,v5);
        double[] bbMid5=sma(c5,20),bbStd5=rollingStd(c5,20);
        double[] e20_15=ema(c15,20),e50_15=ema(c15,50),rsi15=rsi(c15,14),atr15=atr(hi15,lo15,c15,14),vma15=sma(v15,20);
        double[] bbMid15=sma(c15,20),bbStd15=rollingStd(c15,20);
        double[] e20h=ema(ch,20),e50h=ema(ch,50),rsih=rsi(ch,14),atrh=atr(series(h1,'h'),series(h1,'l'),ch,14);

        if(!finite(e9_5[i5],e20_5[i5],rsi5[i5],atr5[i5],vma5[i5],cmf5[i5],obv5[i5],bbMid5[i5],bbStd5[i5],
                e20_15[i15],e50_15[i15],rsi15[i15],atr15[i15],vma15[i15],bbMid15[i15],bbStd15[i15],e20h[ih],e50h[ih],rsih[ih],atrh[ih]))
            return new Decision(null,"NEUTRAL",0,0,0,"AMD unavailable","WARMUP","Indicators are still warming up.");

        double atr5Now=atr5[i5],atr15Now=atr15[i15];
        double body=c5[i5]-o5[i5],range=Math.max(hi5[i5]-lo5[i5],1e-12),bodyRatio=Math.abs(body)/range;
        boolean volumeNormal=v5[i5]>=0.95*vma5[i5];
        boolean volumeStrong=v5[i5]>=1.10*vma5[i5];
        boolean notChasing=Math.abs(c5[i5]-e9_5[i5])<=1.05*atr5Now;

        boolean hUp=e20h[ih]>e50h[ih]&&ch[ih]>e20h[ih]&&e20h[ih]>=e20h[Math.max(0,ih-2)];
        boolean hDown=e20h[ih]<e50h[ih]&&ch[ih]<e20h[ih]&&e20h[ih]<=e20h[Math.max(0,ih-2)];
        boolean m15Up=e20_15[i15]>e50_15[i15]&&c15[i15]>e20_15[i15]&&e20_15[i15]>e20_15[Math.max(0,i15-3)];
        boolean m15Down=e20_15[i15]<e50_15[i15]&&c15[i15]<e20_15[i15]&&e20_15[i15]<e20_15[Math.max(0,i15-3)];

        double width15=4.0*bbStd15[i15]/Math.max(bbMid15[i15],1e-12);
        double avgWidth15=averageBbWidth(bbMid15,bbStd15,Math.max(20,i15-20),i15-1);
        boolean compression15=Double.isFinite(avgWidth15)&&width15<0.90*avgWidth15;
        boolean flat15=Math.abs(e20_15[i15]-e50_15[i15])<=0.65*atr15Now;
        String regime=(hUp&&m15Up)?"TREND_UP":(hDown&&m15Down)?"TREND_DOWN":(compression15||flat15)?"RANGE":"TRANSITION";
        String direction="TREND_UP".equals(regime)?"BUY":"TREND_DOWN".equals(regime)?"SELL":"NEUTRAL";

        List<Candidate> candidates=new ArrayList<>();

        Candidate trend=trendPullbackCandidate(m5,c5,o5,hi5,lo5,v5,e9_5,e20_5,rsi5,atr5,vma5,cmf5,obv5,
                c15,e20_15,e50_15,rsi15,vma15,ch,e20h,e50h,regime,i5,i15,ih,bodyRatio,volumeNormal,volumeStrong,notChasing);
        if(trend!=null)candidates.add(trend);

        Candidate breakout=breakoutRetestCandidate(m5,c5,o5,hi5,lo5,v5,e9_5,e20_5,rsi5,atr5,vma5,cmf5,obv5,
                c15,e20_15,e50_15,rsi15,ch,e20h,e50h,regime,i5,i15,ih,bodyRatio,notChasing);
        if(breakout!=null)candidates.add(breakout);

        Candidate amd=amdCandidate(m5,c5,o5,hi5,lo5,v5,e9_5,e20_5,rsi5,atr5,vma5,cmf5,obv5,
                c15,e20_15,e50_15,ch,e20h,e50h,regime,i5,i15,ih,bodyRatio,notChasing);
        if(amd!=null)candidates.add(amd);

        if(candidates.isEmpty()){
            String amdState=amdDiagnostic(c5,hi5,lo5,atr5,i5,regime);
            int setup=contextScore(regime,hUp,hDown,m15Up,m15Down,rsi15[i15]);
            String summary="No complete v5 setup. Regime="+regime+". A signal now requires one full setup: trend pullback, breakout-retest, or completed AMD sweep + distribution.";
            return new Decision(null,direction,setup,0,0,amdState,regime,summary);
        }

        Collections.sort(candidates,(a,b)->Integer.compare(b.quality,a.quality));
        Candidate best=candidates.get(0);
        Signal s=finishCandidate(best,paperEquity,m5,m15,atr5Now);
        if(s==null){
            return new Decision(null,best.side,best.setupScore,best.triggerScore,best.quality,best.amdState,best.regime,
                    best.type+" formed, but structural SL/nearby liquidity did not provide at least 1:1.25. Trade skipped.");
        }
        return new Decision(s,best.side,best.setupScore,best.triggerScore,best.quality,best.amdState,best.regime,
                best.type+" confirmed. Quality "+best.quality+"/10. Entry is based on completed structure, not a probability claim.");
    }

    private static Candidate trendPullbackCandidate(List<Candle> m5,double[] c,double[] o,double[] h,double[] l,double[] v,
            double[] e9,double[] e20,double[] rsi,double[] atr,double[] vma,double[] cmf,double[] obv,
            double[] c15,double[] e20_15,double[] e50_15,double[] rsi15,double[] vma15,double[] ch,double[] e20h,double[] e50h,
            String regime,int i,int i15,int ih,double bodyRatio,boolean volumeNormal,boolean volumeStrong,boolean notChasing){
        boolean buy="TREND_UP".equals(regime),sell="TREND_DOWN".equals(regime);if(!buy&&!sell)return null;
        int p=i-1;
        boolean aligned=buy?e9[i]>e20[i]&&c[i]>e9[i]:e9[i]<e20[i]&&c[i]<e9[i];
        boolean touched=buy?l[p]<=e20[p]+0.18*atr[i]&&c[p]>=e20[p]-0.10*atr[i]:h[p]>=e20[p]-0.18*atr[i]&&c[p]<=e20[p]+0.10*atr[i];
        boolean reclaim=buy?c[i]>h[p]&&c[i]>e9[i]&&c[i]>o[i]:c[i]<l[p]&&c[i]<e9[i]&&c[i]<o[i];
        boolean momentum=buy?rsi[i]>=50&&rsi[i]<=68&&rsi[i]>rsi[p]:rsi[i]<=50&&rsi[i]>=32&&rsi[i]<rsi[p];
        boolean flow=buy?cmf[i]>0&&obv[i]>=obv[Math.max(0,i-3)]:cmf[i]<0&&obv[i]<=obv[Math.max(0,i-3)];
        boolean bodyOk=bodyRatio>=0.35;
        if(!(aligned&&touched&&reclaim&&bodyOk&&notChasing&&volumeNormal))return null;
        int q=6;
        if(momentum)q++;if(flow)q++;if(volumeStrong)q++;if((buy&&rsi15[i15]>=52&&rsi15[i15]<70)||(sell&&rsi15[i15]<=48&&rsi15[i15]>30))q++;
        if(q<8)return null;
        Candidate x=new Candidate();x.side=buy?"BUY":"SELL";x.type="Trend pullback continuation";x.quality=Math.min(10,q);
        x.setupScore=Math.min(100,70+(q-7)*10);x.triggerScore=Math.min(100,60+(q-6)*10);x.entry=c[i];
        double swing=buy?min(l,Math.max(0,i-8),i):max(h,Math.max(0,i-8),i);
        x.sl=buy?Math.min(x.entry-0.70*atr[i],swing-0.12*atr[i]):Math.max(x.entry+0.70*atr[i],swing+0.12*atr[i]);
        x.targetHint=buy?max(h,Math.max(0,i-36),i-1):min(l,Math.max(0,i-36),i-1);
        x.amdState="Not used — trend regime";x.structureKey=x.type+":"+x.side; x.regime=regime;
        x.rationale="1h+15m trend aligned; 5m pullback touched EMA20 and reclaimed with directional body and volume.";x.time=m5.get(i).closeTime;return x;
    }

    private static Candidate breakoutRetestCandidate(List<Candle> m5,double[] c,double[] o,double[] h,double[] l,double[] v,
            double[] e9,double[] e20,double[] rsi,double[] atr,double[] vma,double[] cmf,double[] obv,
            double[] c15,double[] e20_15,double[] e50_15,double[] rsi15,double[] ch,double[] e20h,double[] e50h,
            String regime,int i,int i15,int ih,double bodyRatio,boolean notChasing){
        if("RANGE".equals(regime))return null; // range must finish AMD instead of pretending to be a breakout trend
        int p=i-1,pp=i-2;
        double priorHigh=max(h,Math.max(0,i-12),i-3),priorLow=min(l,Math.max(0,i-12),i-3);
        boolean buyContext=!"TREND_DOWN".equals(regime)&&e20_15[i15]>=e50_15[i15]&&e20h[ih]>=e50h[ih];
        boolean sellContext=!"TREND_UP".equals(regime)&&e20_15[i15]<=e50_15[i15]&&e20h[ih]<=e50h[ih];
        boolean brokeUp=buyContext&&c[p]>priorHigh+0.05*atr[i]&&v[p]>=1.05*vma[p];
        boolean brokeDown=sellContext&&c[p]<priorLow-0.05*atr[i]&&v[p]>=1.05*vma[p];
        boolean retestUp=brokeUp&&l[i]<=priorHigh+0.15*atr[i]&&l[i]>=priorHigh-0.25*atr[i]&&c[i]>priorHigh&&c[i]>o[i];
        boolean retestDown=brokeDown&&h[i]>=priorLow-0.15*atr[i]&&h[i]<=priorLow+0.25*atr[i]&&c[i]<priorLow&&c[i]<o[i];
        if(!retestUp&&!retestDown)return null;
        boolean buy=retestUp;
        boolean flow=buy?cmf[i]>0&&obv[i]>obv[Math.max(0,i-3)]:cmf[i]<0&&obv[i]<obv[Math.max(0,i-3)];
        boolean momentum=buy?rsi[i]>=50&&rsi[i]<=70:rsi[i]<=50&&rsi[i]>=30;
        boolean volumeOk=v[i]>=0.85*vma[i];
        if(!(bodyRatio>=0.30&&notChasing&&volumeOk&&momentum))return null;
        int q=7;if(flow)q++;if(v[p]>=1.20*vma[p])q++;if((buy&&c15[i15]>e20_15[i15])||(!buy&&c15[i15]<e20_15[i15]))q++;
        if(q<8)return null;
        Candidate x=new Candidate();x.side=buy?"BUY":"SELL";x.type="Breakout retest";x.quality=Math.min(10,q);
        x.setupScore=Math.min(100,65+(q-7)*10);x.triggerScore=Math.min(100,75+(q-7)*10);x.entry=c[i];
        x.sl=buy?Math.min(x.entry-0.65*atr[i],l[i]-0.12*atr[i]):Math.max(x.entry+0.65*atr[i],h[i]+0.12*atr[i]);
        x.targetHint=buy?max(h,Math.max(0,i-48),i-2):min(l,Math.max(0,i-48),i-2);
        x.amdState="Not used — breakout/retest setup";x.structureKey=x.type+":"+x.side+":"+roundLevel(buy?priorHigh:priorLow,x.entry);
        x.regime=regime;x.rationale="5m break occurred first, then a separate closed candle retested the level and held it with directional confirmation.";x.time=m5.get(i).closeTime;return x;
    }

    private static Candidate amdCandidate(List<Candle> m5,double[] c,double[] o,double[] h,double[] l,double[] v,
            double[] e9,double[] e20,double[] rsi,double[] atr,double[] vma,double[] cmf,double[] obv,
            double[] c15,double[] e20_15,double[] e50_15,double[] ch,double[] e20h,double[] e50h,
            String regime,int i,int i15,int ih,double bodyRatio,boolean notChasing){
        if(!"RANGE".equals(regime)&&!"TRANSITION".equals(regime))return null;
        int rangeFrom=Math.max(0,i-28),rangeTo=Math.max(0,i-8);
        double rangeHigh=max(h,rangeFrom,rangeTo),rangeLow=min(l,rangeFrom,rangeTo),width=rangeHigh-rangeLow;
        if(width>5.0*atr[i]||width<1.2*atr[i])return null;
        boolean sweptLow=false,sweptHigh=false;double sweepLow=Double.POSITIVE_INFINITY,sweepHigh=Double.NEGATIVE_INFINITY;
        for(int j=Math.max(1,i-7);j<=i-1;j++){
            if(l[j]<rangeLow-0.08*atr[i]&&c[j]>rangeLow){sweptLow=true;sweepLow=Math.min(sweepLow,l[j]);}
            if(h[j]>rangeHigh+0.08*atr[i]&&c[j]<rangeHigh){sweptHigh=true;sweepHigh=Math.max(sweepHigh,h[j]);}
        }
        double microHigh=max(h,Math.max(0,i-4),i-1),microLow=min(l,Math.max(0,i-4),i-1);
        boolean buy=sweptLow&&c[i]>microHigh&&c[i]>e9[i]&&c[i]>o[i]&&rsi[i]>=50;
        boolean sell=sweptHigh&&c[i]<microLow&&c[i]<e9[i]&&c[i]<o[i]&&rsi[i]<=50;
        if(!buy&&!sell)return null;
        // Do not take AMD directly against a clearly dominant 1h trend.
        if(buy&&e20h[ih]<e50h[ih]&&ch[ih]<e50h[ih]-0.5*Math.abs(e20h[ih]-e50h[ih]))return null;
        if(sell&&e20h[ih]>e50h[ih]&&ch[ih]>e50h[ih]+0.5*Math.abs(e20h[ih]-e50h[ih]))return null;
        boolean flow=buy?cmf[i]>0&&obv[i]>obv[Math.max(0,i-3)]:cmf[i]<0&&obv[i]<obv[Math.max(0,i-3)];
        boolean volumeOk=v[i]>=1.00*vma[i];
        if(!(bodyRatio>=0.35&&notChasing&&volumeOk&&flow))return null;
        int q=8;if(v[i]>=1.20*vma[i])q++;if((buy&&c[i]>e20[i])||(!buy&&c[i]<e20[i]))q++;
        Candidate x=new Candidate();x.side=buy?"BUY":"SELL";x.type="AMD sweep + distribution";x.quality=Math.min(10,q);
        x.setupScore=85;x.triggerScore=Math.min(100,80+(q-8)*10);x.entry=c[i];
        x.sl=buy?Math.min(x.entry-0.75*atr[i],sweepLow-0.15*atr[i]):Math.max(x.entry+0.75*atr[i],sweepHigh+0.15*atr[i]);
        x.targetHint=buy?rangeHigh:rangeLow;
        x.amdState=buy?"AMD LONG complete: accumulation → downside liquidity sweep → bullish distribution":"AMD SHORT complete: accumulation → upside liquidity sweep → bearish distribution";
        x.structureKey=x.type+":"+x.side+":"+roundLevel(buy?rangeLow:rangeHigh,x.entry);x.regime=regime;
        x.rationale="Range was established first, liquidity was swept and reclaimed, then a separate 5m distribution break confirmed the entry.";x.time=m5.get(i).closeTime;return x;
    }

    private static Signal finishCandidate(Candidate x,double equity,List<Candle> m5,List<Candle> m15,double atrNow){
        double risk=Math.abs(x.entry-x.sl);if(!Double.isFinite(risk)||risk<=0)return null;
        if(risk<0.45*atrNow||risk>2.20*atrNow)return null;
        double rrToHint="BUY".equals(x.side)?(x.targetHint-x.entry)/risk:(x.entry-x.targetHint)/risk;
        if(!Double.isFinite(rrToHint)||rrToHint<1.25)return null;
        double rr=Math.min(1.60,rrToHint);
        double tp="BUY".equals(x.side)?x.entry+rr*risk:x.entry-rr*risk;
        double trail="BUY".equals(x.side)?x.entry+0.80*risk:x.entry-0.80*risk;
        double riskBudget=Math.max(0,equity)*0.02;double qty=riskBudget/risk;
        return new Signal(x.side,x.entry,x.sl,tp,risk,qty,trail,rr,x.quality,x.setupScore,x.triggerScore,
                x.type,x.amdState,x.structureKey,x.regime,x.rationale,x.time);
    }

    private static String amdDiagnostic(double[] c,double[] h,double[] l,double[] atr,int i,String regime){
        if(!"RANGE".equals(regime)&&!"TRANSITION".equals(regime))return "AMD blocked — market is in a trend regime";
        double rh=max(h,Math.max(0,i-28),Math.max(0,i-8)),rl=min(l,Math.max(0,i-28),Math.max(0,i-8));
        if(rh-rl>5.0*atr[i])return "No compact accumulation range";
        boolean sl=false,sh=false;for(int j=Math.max(1,i-7);j<=i;j++){if(l[j]<rl-0.08*atr[i]&&c[j]>rl)sl=true;if(h[j]>rh+0.08*atr[i]&&c[j]<rh)sh=true;}
        if(sl)return "Downside liquidity sweep seen; waiting for bullish distribution break";
        if(sh)return "Upside liquidity sweep seen; waiting for bearish distribution break";
        return "Accumulation/range present; waiting for a real liquidity sweep before any AMD entry";
    }

    private static int contextScore(String regime,boolean hUp,boolean hDown,boolean mUp,boolean mDown,double rsi){
        int s=0;if("TREND_UP".equals(regime)){if(hUp)s+=35;if(mUp)s+=35;if(rsi>=50)s+=20;}
        else if("TREND_DOWN".equals(regime)){if(hDown)s+=35;if(mDown)s+=35;if(rsi<=50)s+=20;}
        else if("RANGE".equals(regime))s=60;else s=40;return Math.min(100,s);
    }

    private static String roundLevel(double level,double price){
        double step=price>=10000?10:price>=100?1:price>=1?0.01:0.0001;
        return String.format(Locale.US,"%.8f",Math.round(level/step)*step);
    }

    private static int count(boolean[] a){int n=0;for(boolean x:a)if(x)n++;return n;}
    private static boolean finite(double... xs){for(double x:xs)if(!Double.isFinite(x))return false;return true;}
    private static double[] series(List<Candle> c,char f){double[] o=new double[c.size()];for(int i=0;i<c.size();i++){Candle x=c.get(i);switch(f){case'o':o[i]=x.open;break;case'h':o[i]=x.high;break;case'l':o[i]=x.low;break;case'v':o[i]=x.volume;break;default:o[i]=x.close;}}return o;}
    private static double[] ema(double[] v,int p){double[] o=new double[v.length];Arrays.fill(o,Double.NaN);if(v.length<p)return o;double s=0;for(int i=0;i<p;i++)s+=v[i];double cur=s/p;o[p-1]=cur;double k=2.0/(p+1.0);for(int i=p;i<v.length;i++){cur=(v[i]-cur)*k+cur;o[i]=cur;}return o;}
    private static double[] sma(double[] v,int p){double[] o=new double[v.length];Arrays.fill(o,Double.NaN);double s=0;for(int i=0;i<v.length;i++){s+=v[i];if(i>=p)s-=v[i-p];if(i>=p-1)o[i]=s/p;}return o;}
    private static double[] rollingStd(double[] v,int p){double[] o=new double[v.length];Arrays.fill(o,Double.NaN);for(int i=p-1;i<v.length;i++){double m=0;for(int j=i-p+1;j<=i;j++)m+=v[j];m/=p;double s=0;for(int j=i-p+1;j<=i;j++){double d=v[j]-m;s+=d*d;}o[i]=Math.sqrt(s/p);}return o;}
    private static double[] rsi(double[] v,int p){double[] o=new double[v.length];Arrays.fill(o,Double.NaN);if(v.length<=p)return o;double g=0,l=0;for(int i=1;i<=p;i++){double d=v[i]-v[i-1];g+=Math.max(d,0);l+=Math.max(-d,0);}g/=p;l/=p;o[p]=l==0?100:100-100/(1+g/l);for(int i=p+1;i<v.length;i++){double d=v[i]-v[i-1];g=(g*(p-1)+Math.max(d,0))/p;l=(l*(p-1)+Math.max(-d,0))/p;o[i]=l==0?100:100-100/(1+g/l);}return o;}
    private static double[] atr(double[] h,double[] l,double[] c,int p){double[] o=new double[c.length];Arrays.fill(o,Double.NaN);if(c.length<=p)return o;double[] tr=new double[c.length];tr[0]=h[0]-l[0];for(int i=1;i<c.length;i++)tr[i]=Math.max(h[i]-l[i],Math.max(Math.abs(h[i]-c[i-1]),Math.abs(l[i]-c[i-1])));double cur=0;for(int i=1;i<=p;i++)cur+=tr[i];cur/=p;o[p]=cur;for(int i=p+1;i<c.length;i++){cur=(cur*(p-1)+tr[i])/p;o[i]=cur;}return o;}
    private static double[] obv(double[] c,double[] v){double[] o=new double[c.length];o[0]=0;for(int i=1;i<c.length;i++)o[i]=o[i-1]+(c[i]>c[i-1]?v[i]:c[i]<c[i-1]?-v[i]:0);return o;}
    private static double[] cmf(double[] h,double[] l,double[] c,double[] v,int p){double[] o=new double[c.length];Arrays.fill(o,Double.NaN);double mfv=0,vs=0;for(int i=0;i<c.length;i++){double den=h[i]-l[i];double mult=den==0?0:((c[i]-l[i])-(h[i]-c[i]))/den;double cur=mult*v[i];mfv+=cur;vs+=v[i];if(i>=p){double d=h[i-p]-l[i-p];double m=d==0?0:((c[i-p]-l[i-p])-(h[i-p]-c[i-p]))/d;mfv-=m*v[i-p];vs-=v[i-p];}if(i>=p-1)o[i]=vs==0?0:mfv/vs;}return o;}
    private static double min(double[] a,int f,int t){f=Math.max(0,f);t=Math.min(a.length-1,t);if(f>t)return Double.NaN;double x=Double.POSITIVE_INFINITY;for(int i=f;i<=t;i++)x=Math.min(x,a[i]);return x;}
    private static double max(double[] a,int f,int t){f=Math.max(0,f);t=Math.min(a.length-1,t);if(f>t)return Double.NaN;double x=Double.NEGATIVE_INFINITY;for(int i=f;i<=t;i++)x=Math.max(x,a[i]);return x;}
    private static double averageBbWidth(double[] mid,double[] std,int f,int t){f=Math.max(0,f);t=Math.min(mid.length-1,t);double s=0;int n=0;for(int i=f;i<=t;i++){if(Double.isFinite(mid[i])&&Double.isFinite(std[i])&&mid[i]!=0){s+=4.0*std[i]/Math.abs(mid[i]);n++;}}return n==0?Double.NaN:s/n;}
}

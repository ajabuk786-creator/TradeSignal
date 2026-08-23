package com.tradesignal.app;

import java.util.*;

/**
 * TradeSignal v5.1 balanced scalp engine.
 *
 * Goals:
 * - keep structure-first entries rather than correlated indicator voting
 * - allow valid scalp patterns to complete over short multi-candle windows
 * - keep 1h context, 15m structure, and 5m confirmation
 * - support TREND_PULLBACK, BREAKOUT_RETEST and AMD_SWEEP_DISTRIBUTION
 * - use structural invalidation for SL and nearby-liquidity-aware TP
 * - expose a 1..10 quality score, never a claimed win probability
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
        double entry,sl,targetHint,atr;
        long time,anchorTime;
    }

    public static Decision analyzeScalp(List<Candle> m5,List<Candle> m15,List<Candle> h1,double paperEquity,int ignoredThreshold){
        if(m5==null||m15==null||h1==null||m5.size()<120||m15.size()<90||h1.size()<70)
            return new Decision(null,"NEUTRAL",0,0,0,"AMD unavailable","WARMUP","Not enough closed candles for v5.1 structure analysis.");

        int i5=m5.size()-1,i15=m15.size()-1,ih=h1.size()-1;
        double[] c5=series(m5,'c'),o5=series(m5,'o'),hi5=series(m5,'h'),lo5=series(m5,'l'),v5=series(m5,'v');
        double[] c15=series(m15,'c'),hi15=series(m15,'h'),lo15=series(m15,'l'),v15=series(m15,'v');
        double[] ch=series(h1,'c'),hh=series(h1,'h'),lh=series(h1,'l');

        double[] e9_5=ema(c5,9),e20_5=ema(c5,20),rsi5=rsi(c5,14),atr5=atr(hi5,lo5,c5,14),vma5=sma(v5,20),cmf5=cmf(hi5,lo5,c5,v5,20),obv5=obv(c5,v5);
        double[] bbMid5=sma(c5,20),bbStd5=rollingStd(c5,20);
        double[] e20_15=ema(c15,20),e50_15=ema(c15,50),rsi15=rsi(c15,14),atr15=atr(hi15,lo15,c15,14),vma15=sma(v15,20);
        double[] bbMid15=sma(c15,20),bbStd15=rollingStd(c15,20);
        double[] e20h=ema(ch,20),e50h=ema(ch,50),rsih=rsi(ch,14),atrh=atr(hh,lh,ch,14);

        if(!finite(e9_5[i5],e20_5[i5],rsi5[i5],atr5[i5],vma5[i5],cmf5[i5],obv5[i5],bbMid5[i5],bbStd5[i5],
                e20_15[i15],e50_15[i15],rsi15[i15],atr15[i15],vma15[i15],bbMid15[i15],bbStd15[i15],
                e20h[ih],e50h[ih],rsih[ih],atrh[ih]))
            return new Decision(null,"NEUTRAL",0,0,0,"AMD unavailable","WARMUP","Indicators are still warming up.");

        double atr5Now=atr5[i5],atr15Now=atr15[i15];
        double width15=4.0*bbStd15[i15]/Math.max(bbMid15[i15],1e-12);
        double avgWidth15=averageBbWidth(bbMid15,bbStd15,Math.max(20,i15-20),i15-1);
        boolean compression15=Double.isFinite(avgWidth15)&&width15<0.90*avgWidth15;
        boolean flat15=Math.abs(e20_15[i15]-e50_15[i15])<=0.55*atr15Now;

        boolean hBull=e20h[ih]>e50h[ih]&&ch[ih]>=e20h[ih]&&e20h[ih]>=e20h[Math.max(0,ih-2)];
        boolean hBear=e20h[ih]<e50h[ih]&&ch[ih]<=e20h[ih]&&e20h[ih]<=e20h[Math.max(0,ih-2)];
        boolean m15Bull=(e20_15[i15]>e50_15[i15]&&c15[i15]>=e20_15[i15]) ||
                (c15[i15]>e20_15[i15]&&e20_15[i15]>e20_15[Math.max(0,i15-3)]&&rsi15[i15]>=50);
        boolean m15Bear=(e20_15[i15]<e50_15[i15]&&c15[i15]<=e20_15[i15]) ||
                (c15[i15]<e20_15[i15]&&e20_15[i15]<e20_15[Math.max(0,i15-3)]&&rsi15[i15]<=50);

        String regime;
        if(hBull&&m15Bull&&!compression15) regime="TREND_UP";
        else if(hBear&&m15Bear&&!compression15) regime="TREND_DOWN";
        else if(compression15||flat15) regime="RANGE";
        else regime="TRANSITION";
        String direction="TREND_UP".equals(regime)?"BUY":"TREND_DOWN".equals(regime)?"SELL":"NEUTRAL";

        double bodyRatio=Math.abs(c5[i5]-o5[i5])/Math.max(hi5[i5]-lo5[i5],1e-12);
        boolean volumeNormal=v5[i5]>=0.80*vma5[i5];
        boolean volumeStrong=v5[i5]>=1.05*vma5[i5];
        boolean notChasing=Math.abs(c5[i5]-e9_5[i5])<=1.25*atr5Now;

        List<Candidate> candidates=new ArrayList<>();
        Candidate trend=trendPullbackCandidate(m5,c5,o5,hi5,lo5,v5,e9_5,e20_5,rsi5,atr5,vma5,cmf5,obv5,
                c15,e20_15,e50_15,rsi15,regime,i5,i15,bodyRatio,volumeNormal,volumeStrong,notChasing);
        if(trend!=null)candidates.add(trend);

        Candidate breakout=breakoutRetestCandidate(m5,c5,o5,hi5,lo5,v5,e9_5,e20_5,rsi5,atr5,vma5,cmf5,obv5,
                c15,e20_15,e50_15,rsi15,hBull,hBear,m15Bull,m15Bear,regime,i5,i15,bodyRatio,notChasing);
        if(breakout!=null)candidates.add(breakout);

        Candidate amd=amdCandidate(m5,c5,o5,hi5,lo5,v5,e9_5,e20_5,rsi5,atr5,vma5,cmf5,obv5,
                regime,i5,bodyRatio,notChasing);
        if(amd!=null)candidates.add(amd);

        if(candidates.isEmpty()){
            String amdState=amdDiagnostic(c5,hi5,lo5,atr5,i5,regime);
            int setup=contextScore(regime,hBull,hBear,m15Bull,m15Bear,rsi15[i15]);
            String summary="No complete v5.1 setup yet. Regime="+regime+". The scanner now allows multi-candle pullback/retest windows, but still waits for structural confirmation before entry.";
            return new Decision(null,direction,setup,0,0,amdState,regime,summary);
        }

        Collections.sort(candidates,(a,b)->Integer.compare(b.quality,a.quality));
        Candidate best=candidates.get(0);
        Signal s=finishCandidate(best,paperEquity,m5,m15);
        if(s==null){
            return new Decision(null,best.side,best.setupScore,best.triggerScore,best.quality,best.amdState,best.regime,
                    best.type+" formed, but stop/target structure was not suitable for a scalp. Trade skipped.");
        }
        return new Decision(s,best.side,best.setupScore,best.triggerScore,best.quality,best.amdState,best.regime,
                best.type+" confirmed. Quality "+best.quality+"/10. This is a structure score, not a win probability.");
    }

    private static Candidate trendPullbackCandidate(List<Candle> m5,double[] c,double[] o,double[] h,double[] l,double[] v,
            double[] e9,double[] e20,double[] rsi,double[] atr,double[] vma,double[] cmf,double[] obv,
            double[] c15,double[] e20_15,double[] e50_15,double[] rsi15,String regime,int i,int i15,
            double bodyRatio,boolean volumeNormal,boolean volumeStrong,boolean notChasing){
        boolean buy="TREND_UP".equals(regime),sell="TREND_DOWN".equals(regime);if(!buy&&!sell)return null;
        int pb=-1;
        for(int j=Math.max(25,i-4);j<=i-1;j++){
            boolean touch=buy?l[j]<=e20[j]+0.25*atr[j]&&c[j]>=e20[j]-0.20*atr[j]
                             :h[j]>=e20[j]-0.25*atr[j]&&c[j]<=e20[j]+0.20*atr[j];
            if(touch)pb=j;
        }
        if(pb<0)return null;
        double triggerLevel=buy?max(h,pb,i-1):min(l,pb,i-1);
        boolean aligned=buy?e9[i]>=e20[i]&&c[i]>e9[i]:e9[i]<=e20[i]&&c[i]<e9[i];
        boolean confirm=buy?c[i]>triggerLevel&&c[i]>o[i]:c[i]<triggerLevel&&c[i]<o[i];
        boolean momentum=buy?rsi[i]>=49&&rsi[i]<=70&&rsi[i]>=rsi[Math.max(pb,i-1)]-1
                            :rsi[i]<=51&&rsi[i]>=30&&rsi[i]<=rsi[Math.max(pb,i-1)]+1;
        boolean flow=buy?cmf[i]>=-0.02&&obv[i]>=obv[Math.max(0,i-3)]
                         :cmf[i]<=0.02&&obv[i]<=obv[Math.max(0,i-3)];
        boolean bodyOk=bodyRatio>=0.28;
        if(!(aligned&&confirm&&bodyOk&&notChasing&&volumeNormal&&momentum))return null;
        int q=6;if(flow)q++;if(volumeStrong)q++;if((buy&&rsi15[i15]>=51)||(sell&&rsi15[i15]<=49))q++;
        boolean wickReject=buy?(c[pb]-l[pb])>=0.35*Math.max(h[pb]-l[pb],1e-12):(h[pb]-c[pb])>=0.35*Math.max(h[pb]-l[pb],1e-12);
        if(wickReject)q++;if(q<7)return null;
        Candidate x=new Candidate();x.side=buy?"BUY":"SELL";x.type="Trend pullback continuation";x.quality=Math.min(10,q);
        x.setupScore=Math.min(100,65+q*4);x.triggerScore=Math.min(100,60+q*5);x.entry=c[i];x.atr=atr[i];
        double swing=buy?min(l,Math.max(0,pb-2),i):max(h,Math.max(0,pb-2),i);
        x.sl=buy?swing-0.12*atr[i]:swing+0.12*atr[i];
        x.targetHint=buy?nearestAbove(h,x.entry,Math.max(0,i-48),i-1):nearestBelow(l,x.entry,Math.max(0,i-48),i-1);
        x.amdState="Not used — trend pullback setup";x.anchorTime=m5.get(pb).closeTime;
        x.structureKey=structureKey(x.type,x.side,x.anchorTime,triggerLevel,x.atr);x.regime=regime;
        x.rationale="1h/15m trend context; pullback occurred within the last four 5m candles and a later 5m candle reclaimed/broke structure with momentum and volume.";
        x.time=m5.get(i).closeTime;return x;
    }

    private static Candidate breakoutRetestCandidate(List<Candle> m5,double[] c,double[] o,double[] h,double[] l,double[] v,
            double[] e9,double[] e20,double[] rsi,double[] atr,double[] vma,double[] cmf,double[] obv,
            double[] c15,double[] e20_15,double[] e50_15,double[] rsi15,boolean hBull,boolean hBear,boolean m15Bull,boolean m15Bear,
            String regime,int i,int i15,double bodyRatio,boolean notChasing){
        if("RANGE".equals(regime))return null;
        boolean buyContext=!hBear&&(hBull||m15Bull||c15[i15]>e20_15[i15]);
        boolean sellContext=!hBull&&(hBear||m15Bear||c15[i15]<e20_15[i15]);
        Candidate best=null;
        for(int b=Math.max(30,i-5);b<=i-2;b++){
            double priorHigh=max(h,Math.max(0,b-12),b-1),priorLow=min(l,Math.max(0,b-12),b-1);
            boolean brokeUp=buyContext&&c[b]>priorHigh+0.03*atr[b]&&v[b]>=0.95*vma[b]&&c[b]>o[b];
            boolean brokeDn=sellContext&&c[b]<priorLow-0.03*atr[b]&&v[b]>=0.95*vma[b]&&c[b]<o[b];
            if(!brokeUp&&!brokeDn)continue;
            boolean buy=brokeUp;double level=buy?priorHigh:priorLow;int r=-1;
            for(int j=b+1;j<=i-1;j++){
                boolean touched=buy?l[j]<=level+0.25*atr[j]&&l[j]>=level-0.35*atr[j]
                                   :h[j]>=level-0.25*atr[j]&&h[j]<=level+0.35*atr[j];
                boolean held=buy?c[j]>=level:c[j]<=level;
                if(touched&&held){r=j;break;}
            }
            if(r<0)continue;
            double reclaim=buy?max(h,r,i-1):min(l,r,i-1);
            boolean confirm=buy?c[i]>reclaim&&c[i]>o[i]&&c[i]>level:c[i]<reclaim&&c[i]<o[i]&&c[i]<level;
            boolean momentum=buy?rsi[i]>=50&&rsi[i]<=72:rsi[i]<=50&&rsi[i]>=28;
            boolean volume=v[i]>=0.80*vma[i];
            boolean flow=buy?cmf[i]>=-0.02&&obv[i]>=obv[Math.max(0,i-3)]:cmf[i]<=0.02&&obv[i]<=obv[Math.max(0,i-3)];
            if(!(confirm&&momentum&&volume&&notChasing&&bodyRatio>=0.25))continue;
            int q=7;if(v[b]>=1.15*vma[b])q++;if(flow)q++;if((buy&&rsi15[i15]>=50)||( !buy&&rsi15[i15]<=50))q++;
            Candidate x=new Candidate();x.side=buy?"BUY":"SELL";x.type="Breakout retest";x.quality=Math.min(10,q);
            x.setupScore=Math.min(100,65+q*4);x.triggerScore=Math.min(100,65+q*4);x.entry=c[i];x.atr=atr[i];
            double invalid=buy?min(l,r,i):max(h,r,i);x.sl=buy?invalid-0.12*atr[i]:invalid+0.12*atr[i];
            x.targetHint=buy?nearestAbove(h,x.entry,Math.max(0,i-60),b-1):nearestBelow(l,x.entry,Math.max(0,i-60),b-1);
            x.amdState="Not used — breakout/retest setup";x.anchorTime=m5.get(b).closeTime;
            x.structureKey=structureKey(x.type,x.side,x.anchorTime,level,x.atr);x.regime=regime;
            x.rationale="Breakout occurred within the last five 5m candles, a later candle retested and held the level, and the current closed candle confirmed continuation.";
            x.time=m5.get(i).closeTime;if(best==null||x.quality>best.quality)best=x;
        }
        return best;
    }

    private static Candidate amdCandidate(List<Candle> m5,double[] c,double[] o,double[] h,double[] l,double[] v,
            double[] e9,double[] e20,double[] rsi,double[] atr,double[] vma,double[] cmf,double[] obv,
            String regime,int i,double bodyRatio,boolean notChasing){
        if(i<35)return null;
        int rangeFrom=i-24,rangeTo=i-9;
        double rangeHigh=max(h,rangeFrom,rangeTo),rangeLow=min(l,rangeFrom,rangeTo),rangeWidth=rangeHigh-rangeLow;
        if(rangeWidth>5.8*atr[i])return null;
        Candidate best=null;
        for(int s=i-8;s<=i-2;s++){
            boolean sweepLow=l[s]<rangeLow-0.04*atr[s]&&c[s]>=rangeLow-0.10*atr[s];
            boolean sweepHigh=h[s]>rangeHigh+0.04*atr[s]&&c[s]<=rangeHigh+0.10*atr[s];
            if(!sweepLow&&!sweepHigh)continue;
            boolean buy=sweepLow;double sweepExtreme=buy?l[s]:h[s];
            int reclaim=-1;
            for(int j=s;j<=i-1;j++){
                boolean ok=buy?c[j]>rangeLow&&c[j]>e9[j]:c[j]<rangeHigh&&c[j]<e9[j];
                if(ok){reclaim=j;break;}
            }
            if(reclaim<0)continue;
            double distLevel=buy?max(h,reclaim,i-1):min(l,reclaim,i-1);
            boolean distribution=buy?c[i]>distLevel&&c[i]>o[i]:c[i]<distLevel&&c[i]<o[i];
            boolean momentum=buy?rsi[i]>=49&&rsi[i]<=70:rsi[i]<=51&&rsi[i]>=30;
            boolean volume=v[i]>=0.85*vma[i];
            boolean flow=buy?cmf[i]>=0&&obv[i]>=obv[Math.max(0,i-3)]:cmf[i]<=0&&obv[i]<=obv[Math.max(0,i-3)];
            if(!(distribution&&momentum&&volume&&notChasing&&bodyRatio>=0.25))continue;
            int q=7;if(flow)q++;if(v[i]>=1.05*vma[i])q++;if((buy&&c[i]>e20[i])||(!buy&&c[i]<e20[i]))q++;
            Candidate x=new Candidate();x.side=buy?"BUY":"SELL";x.type="AMD sweep + distribution";x.quality=Math.min(10,q);
            x.setupScore=Math.min(100,70+q*3);x.triggerScore=Math.min(100,65+q*4);x.entry=c[i];x.atr=atr[i];
            x.sl=buy?sweepExtreme-0.12*atr[i]:sweepExtreme+0.12*atr[i];
            x.targetHint=buy?rangeHigh:rangeLow;
            x.amdState=buy?"AMD LONG complete: accumulation → downside sweep → reclaim → distribution"
                          :"AMD SHORT complete: accumulation → upside sweep → reclaim → distribution";
            x.anchorTime=m5.get(s).closeTime;x.structureKey=structureKey(x.type,x.side,x.anchorTime,buy?rangeLow:rangeHigh,x.atr);
            x.regime=regime;x.rationale="Completed AMD sequence with a real liquidity sweep, reclaim and later distribution break on a closed 5m candle.";
            x.time=m5.get(i).closeTime;if(best==null||x.quality>best.quality)best=x;
        }
        return best;
    }

    private static Signal finishCandidate(Candidate x,double equity,List<Candle> m5,List<Candle> m15){
        if(x==null||!finite(x.entry,x.sl,x.atr)||x.atr<=0)return null;
        boolean buy="BUY".equals(x.side);double risk=Math.abs(x.entry-x.sl);
        double minRisk=0.55*x.atr,maxRisk=2.40*x.atr;
        if(risk<minRisk){x.sl=buy?x.entry-minRisk:x.entry+minRisk;risk=minRisk;}
        if(risk>maxRisk)return null;

        double liquidity=x.targetHint;
        if(!finite(liquidity)||(buy&&liquidity<=x.entry)||(!buy&&liquidity>=x.entry)){
            double[] hh=series(m15,'h'),ll=series(m15,'l');
            liquidity=buy?nearestAbove(hh,x.entry,Math.max(0,hh.length-50),hh.length-2)
                         :nearestBelow(ll,x.entry,Math.max(0,ll.length-50),ll.length-2);
        }
        double rrTarget=1.25;
        if(finite(liquidity)){
            double available=Math.abs(liquidity-x.entry)/risk;
            if(available<1.10)return null;
            rrTarget=Math.min(1.40,Math.max(1.15,available*0.88));
        }
        double tp=buy?x.entry+rrTarget*risk:x.entry-rrTarget*risk;
        double trail=buy?x.entry+0.75*risk:x.entry-0.75*risk;
        double riskBudget=Math.max(0,equity)*0.02,qty=riskBudget/risk;
        if(!finite(tp,trail,qty)||qty<=0)return null;
        return new Signal(x.side,x.entry,x.sl,tp,risk,qty,trail,rrTarget,x.quality,x.setupScore,x.triggerScore,
                x.type,x.amdState,x.structureKey,x.regime,x.rationale,x.time);
    }

    private static String amdDiagnostic(double[] c,double[] h,double[] l,double[] atr,int i,String regime){
        if(i<30)return "AMD unavailable";double rh=max(h,i-24,i-9),rl=min(l,i-24,i-9),w=rh-rl;
        if(w<=5.8*atr[i]){
            boolean sweptLow=false,sweptHigh=false;for(int j=i-8;j<=i-1;j++){if(l[j]<rl-0.04*atr[j])sweptLow=true;if(h[j]>rh+0.04*atr[j])sweptHigh=true;}
            if(sweptLow&&!sweptHigh)return "AMD range + downside sweep detected; waiting for reclaim/distribution confirmation";
            if(sweptHigh&&!sweptLow)return "AMD range + upside sweep detected; waiting for reclaim/distribution confirmation";
            if(sweptLow&&sweptHigh)return "AMD range has swept both sides; waiting for clean distribution direction";
            return "AMD accumulation/range present; no completed liquidity sweep yet";
        }
        return "No clean AMD range in current structure";
    }

    private static int contextScore(String regime,boolean hBull,boolean hBear,boolean m15Bull,boolean m15Bear,double rsi15){
        int s=0;if(hBull||hBear)s+=35;if(m15Bull||m15Bear)s+=35;if("TREND_UP".equals(regime)||"TREND_DOWN".equals(regime))s+=20;
        if(rsi15>=45&&rsi15<=55)s+=5;else s+=10;return Math.min(100,s);
    }

    private static String structureKey(String type,String side,long anchor,double level,double atr){
        double step=Math.max(atr*0.50,Math.abs(level)*0.0001);long bucket=Math.round(level/Math.max(step,1e-12));
        long timeBucket=anchor/(15L*60L*1000L);return type+"|"+side+"|"+timeBucket+"|"+bucket;
    }

    private static double[] series(List<Candle> c,char field){double[] out=new double[c.size()];for(int i=0;i<c.size();i++){Candle x=c.get(i);switch(field){case'o':out[i]=x.open;break;case'h':out[i]=x.high;break;case'l':out[i]=x.low;break;case'v':out[i]=x.volume;break;default:out[i]=x.close;}}return out;}
    private static double[] ema(double[] v,int p){double[] out=new double[v.length];Arrays.fill(out,Double.NaN);if(v.length<p)return out;double sum=0;for(int i=0;i<p;i++)sum+=v[i];double cur=sum/p;out[p-1]=cur;double k=2.0/(p+1.0);for(int i=p;i<v.length;i++){cur=(v[i]-cur)*k+cur;out[i]=cur;}return out;}
    private static double[] sma(double[] v,int p){double[] out=new double[v.length];Arrays.fill(out,Double.NaN);double sum=0;for(int i=0;i<v.length;i++){sum+=v[i];if(i>=p)sum-=v[i-p];if(i>=p-1)out[i]=sum/p;}return out;}
    private static double[] rollingStd(double[] v,int p){double[] out=new double[v.length];Arrays.fill(out,Double.NaN);for(int i=p-1;i<v.length;i++){double m=0;for(int j=i-p+1;j<=i;j++)m+=v[j];m/=p;double s=0;for(int j=i-p+1;j<=i;j++){double d=v[j]-m;s+=d*d;}out[i]=Math.sqrt(s/p);}return out;}
    private static double[] rsi(double[] v,int p){double[] out=new double[v.length];Arrays.fill(out,Double.NaN);if(v.length<=p)return out;double g=0,l=0;for(int i=1;i<=p;i++){double d=v[i]-v[i-1];g+=Math.max(d,0);l+=Math.max(-d,0);}g/=p;l/=p;out[p]=l==0?100:100-100/(1+g/l);for(int i=p+1;i<v.length;i++){double d=v[i]-v[i-1];g=(g*(p-1)+Math.max(d,0))/p;l=(l*(p-1)+Math.max(-d,0))/p;out[i]=l==0?100:100-100/(1+g/l);}return out;}
    private static double[] atr(double[] h,double[] l,double[] c,int p){double[] out=new double[c.length];Arrays.fill(out,Double.NaN);if(c.length<=p)return out;double[] tr=new double[c.length];tr[0]=h[0]-l[0];for(int i=1;i<c.length;i++)tr[i]=Math.max(h[i]-l[i],Math.max(Math.abs(h[i]-c[i-1]),Math.abs(l[i]-c[i-1])));double cur=0;for(int i=1;i<=p;i++)cur+=tr[i];cur/=p;out[p]=cur;for(int i=p+1;i<c.length;i++){cur=(cur*(p-1)+tr[i])/p;out[i]=cur;}return out;}
    private static double[] obv(double[] c,double[] v){double[] out=new double[c.length];out[0]=0;for(int i=1;i<c.length;i++)out[i]=out[i-1]+(c[i]>c[i-1]?v[i]:c[i]<c[i-1]?-v[i]:0);return out;}
    private static double[] cmf(double[] h,double[] l,double[] c,double[] v,int p){double[] out=new double[c.length];Arrays.fill(out,Double.NaN);double mfvSum=0,vSum=0;double[] mfv=new double[c.length];for(int i=0;i<c.length;i++){double range=h[i]-l[i],mfm=range==0?0:((c[i]-l[i])-(h[i]-c[i]))/range;mfv[i]=mfm*v[i];mfvSum+=mfv[i];vSum+=v[i];if(i>=p){mfvSum-=mfv[i-p];vSum-=v[i-p];}if(i>=p-1)out[i]=vSum==0?0:mfvSum/vSum;}return out;}
    private static double averageBbWidth(double[] mid,double[] std,int from,int to){from=Math.max(0,from);to=Math.min(mid.length-1,to);double sum=0;int n=0;for(int i=from;i<=to;i++){if(Double.isFinite(mid[i])&&Double.isFinite(std[i])&&mid[i]!=0){sum+=4.0*std[i]/Math.abs(mid[i]);n++;}}return n==0?Double.NaN:sum/n;}
    private static boolean finite(double...x){for(double v:x)if(!Double.isFinite(v))return false;return true;}
    private static double min(double[] a,int from,int to){from=Math.max(0,from);to=Math.min(a.length-1,to);double x=Double.POSITIVE_INFINITY;for(int i=from;i<=to;i++)x=Math.min(x,a[i]);return x;}
    private static double max(double[] a,int from,int to){from=Math.max(0,from);to=Math.min(a.length-1,to);double x=Double.NEGATIVE_INFINITY;for(int i=from;i<=to;i++)x=Math.max(x,a[i]);return x;}
    private static double nearestAbove(double[] a,double entry,int from,int to){from=Math.max(0,from);to=Math.min(a.length-1,to);double best=Double.NaN;for(int i=from;i<=to;i++){double x=a[i];if(x>entry&&(!Double.isFinite(best)||x<best))best=x;}return best;}
    private static double nearestBelow(double[] a,double entry,int from,int to){from=Math.max(0,from);to=Math.min(a.length-1,to);double best=Double.NaN;for(int i=from;i<=to;i++){double x=a[i];if(x<entry&&(!Double.isFinite(best)||x>best))best=x;}return best;}
}

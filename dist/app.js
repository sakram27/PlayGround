// Demo chart + backtest sederhana (mirip PaperEngine.kt di APK)
function genCandles(n=90, start=30000){
  let p=start; const out=[];
  for(let i=0;i<n;i++){ const o=p; const c=o+(Math.random()-0.48)*o*0.02;
    const h=Math.max(o,c)+Math.random()*o*0.005, l=Math.min(o,c)-Math.random()*o*0.005;
    out.push({o,h,l,c}); p=c; }
  return out;
}
function drawCandles(id, data){
  const cv=document.getElementById(id); if(!cv) return;
  const ctx=cv.getContext('2d'); const W=cv.width,H=cv.height; ctx.clearRect(0,0,W,H);
  const min=Math.min(...data.map(d=>d.l)), max=Math.max(...data.map(d=>d.h));
  const rng=(max-min)||1, bw=W/data.length;
  data.forEach((d,i)=>{ const y=v=>H-((v-min)/rng*H);
    const up=d.c>=d.o, col=up?'#26a69a':'#ef5350'; ctx.strokeStyle=col; ctx.fillStyle=col;
    const x=i*bw+bw/2; ctx.beginPath(); ctx.moveTo(x,y(d.h)); ctx.lineTo(x,y(d.l)); ctx.stroke();
    const t=y(Math.max(d.o,d.c)), b=y(Math.min(d.o,d.c));
    ctx.fillRect(x-bw*0.3,t,bw*0.6,Math.max(2,b-t)); });
}
function sma(cl,p){ return cl.map((_,i)=> i+1<p?null:cl.slice(i+1-p,i+1).reduce((a,b)=>a+b,0)/p); }
function rsi(cl,p=14){ const out=Array(cl.length).fill(null); if(cl.length<=p) return out;
  let g=0,l=0; for(let i=1;i<=p;i++){const d=cl[i]-cl[i-1]; if(d>=0)g+=d; else l-=d;} g/=p;l/=p;
  out[p]=l===0?100:100-100/(1+g/l);
  for(let i=p+1;i<cl.length;i++){const d=cl[i]-cl[i-1]; g=(g*(p-1)+Math.max(d,0))/p; l=(l*(p-1)+Math.max(-d,0))/p;
    out[i]=l===0?100:100-100/(1+g/l);} return out; }
function backtest(strategy, data){
  const cl=data.map(d=>d.c); let inPos=false,entry=0; const profits=[];
  const s9=sma(cl,9),s21=sma(cl,21),r=rsi(cl);
  const e20=(()=>{const k=2/21;let prev=cl.slice(0,20).reduce((a,b)=>a+b,0)/20;return cl.map((_,i)=>{if(i<19)return null;prev=i===19?prev:cl[i]*k+prev*(1-k);return prev;});})();
  for(let i=0;i<data.length;i++){ let sig='hold';
    if(strategy==='sma'&&i>21&&s9[i]!=null){ if(s9[i-1]<=s21[i-1]&&s9[i]>s21[i])sig='buy'; if(s9[i-1]>=s21[i-1]&&s9[i]<s21[i])sig='sell'; }
    if(strategy==='rsi'&&r[i]!=null){ if(r[i]<30)sig='buy'; if(r[i]>70)sig='sell'; }
    if(strategy==='ema'&&e20[i]!=null&&r[i]!=null){ if(cl[i]>e20[i]&&r[i]>50)sig='buy'; if(cl[i]<e20[i])sig='sell'; }
    if(!inPos&&sig==='buy'){inPos=true;entry=cl[i];}
    else if(inPos&&sig==='sell'){profits.push((cl[i]-entry)/entry*100-0.2);inPos=false;} }
  const wins=profits.filter(p=>p>0).length;
  return {n:profits.length,wr:profits.length?wins*100/profits.length:0,tot:profits.reduce((a,b)=>a+b,0)};
}
const heroData=genCandles(40,67000); drawCandles('heroChart',heroData);
let bal=1000;
document.getElementById('heroBuy').onclick=()=>{ bal-=50; document.getElementById('heroMsg').textContent='BUY BTC paper @ '+heroData[heroData.length-1].c.toFixed(0)+' • Saldo $'+bal.toFixed(0); };
const demoData=genCandles(90,67000); drawCandles('demoChart',demoData);
document.getElementById('runBt').onclick=()=>{
  const s=document.getElementById('strat').value;
  const r=backtest(s,demoData);
  document.getElementById('btResult').textContent=`Trades: ${r.n} • Winrate: ${r.wr.toFixed(1)}% • Total: ${r.tot.toFixed(2)}%`;
};

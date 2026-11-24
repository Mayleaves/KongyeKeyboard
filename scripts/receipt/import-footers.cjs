// Import the four inspected vector footer designs from the supplied workbench.
const fs=require('node:fs'),path=require('node:path');
const source=fs.readFileSync(process.argv[2],'utf8');
const start=source.indexOf('function buildCode128SVG()');
const end=source.indexOf('const LOGOS =',start);
const footers=new Function(source.slice(start,end)+';return FOOTERS;')();
const dest=path.resolve(__dirname,'../../app/src/main/assets/receipt');
for(const [i,footer] of Object.values(footers).entries()) {
  const raw=footer.render(),svg=raw.match(/<svg\b[\s\S]*?<\/svg>/)[0];
  const box=svg.match(/viewBox="0 0 ([.\d]+) ([.\d]+)"/),w=+box[1];
  const inner=svg.slice(svg.indexOf('>')+1,svg.lastIndexOf('</svg>'));
  let texts='';
  const text=(y,value,size,fill,font,spacing)=>`<text x="108" y="${y}" text-anchor="middle" fill="var(--r-${fill})" style="font-family:${font};font-size:${size}px;letter-spacing:${spacing}em;">${value}</text>`;
  if(i===0)texts=text(43,'空叶键盘',12,'text','serif',.35)+text(60,'KONGYE KEYBOARD · EST. 2026',7.8,'sub','sans-serif',.22);
  if(i===1)texts=text(28,'KY · {{serial}} · {{keys}}',7.6,'sub','monospace',.16)+text(51,'空叶键盘',11.5,'text','serif',.3);
  if(i===2)texts=text(43,'空叶键盘',11.8,'text','serif',.34)+text(60,'CADENCE OF {{keys}} KEYS',7.4,'sub','monospace',.2);
  const output=`<svg width="216" height="65" viewBox="0 0 216 65" fill="none" xmlns="http://www.w3.org/2000/svg"><g transform="translate(${(216-w)/2} ${i===3?12:0})">${inner}</g>${texts}</svg>`;
  fs.writeFileSync(path.join(dest,'footer_'+i+'.svg'),output);
}

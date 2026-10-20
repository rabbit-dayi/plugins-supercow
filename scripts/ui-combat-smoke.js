// TEST ONLY: requires OP, existing pet, flat floor at y=200. Changes skill toggles, follow distance, mode and inventory.
// Back up and restore test config separately. Never run on a production world.
const mf=require('mineflayer'),assert=require('assert');
if(process.env.SC_TEST_MUTATIONS!=='1') throw Error('Test-only mutations: explicitly set SC_TEST_MUTATIONS=1 on an isolated server');
const port=Number(process.env.SC_TEST_PORT||25566);if(port===25565) throw Error('Refusing production port');
const bot=mf.createBot({host:'127.0.0.1',port,username:process.env.SC_TEST_USER||'TestBot',version:'1.16.5'});
const sleep=ms=>new Promise(r=>setTimeout(r,ms)); const msgs=[]; const bars=[];
bot.on('message',(m,pos)=>{msgs.push(m.toString());if(pos==='game_info')bars.push(m.toString());});
bot.on('error',e=>{console.error(e);process.exit(1)});
async function cmd(c,t=250){bot.chat(c);await sleep(t)}
function lore(slot){const nbt=bot.currentWindow?.slots[slot]?.nbt;if(!nbt)return '';const lines=require('prismarine-nbt').simplify(nbt).display?.Lore||[];return lines.map(s=>{const c=JSON.parse(s);assert(c&&typeof c==='object','Lore must be a JSON component');return JSON.stringify(c)}).join('\n');}
function name(p){return JSON.stringify(p.metadata[2]||'');}
function click(p,hand=0,at=false){bot._client.write('use_entity',at?{target:p.id,mouse:2,x:0,y:.5,z:0,hand,sneaking:false}:{target:p.id,mouse:0,hand,sneaking:false});}
async function shut(){if(bot.currentWindow){bot.closeWindow(bot.currentWindow);await sleep(250)}}
bot.once('spawn',async()=>{try{
 bot.physicsEnabled=false; await sleep(1500);await cmd('/gamemode creative');await cmd('/supercow mode passive');await cmd('/supercow call');await cmd('/tp TestBot 0.5 201 0.5');await cmd('/tp @e[tag=supercow_pet,limit=1,sort=nearest] 1.5 201 0.5'); await cmd('/clear TestBot');
 const p=Object.values(bot.entities).find(e=>e.name==='cow'&&name(e).includes('超级牛'));assert(p,'pet visible');assert(p.position.distanceTo(bot.entity.position)<4,'near pet');
 click(p,0,true);click(p);click(p,1);await sleep(250);assert(!bot.currentWindow,'single click + duplicate hands must not open');
 click(p,0,true);click(p);await sleep(250);assert(!bot.currentWindow,'two clicks must not open');
 click(p,0,true);click(p);await sleep(450);assert(bot.currentWindow,'third click opens');assert(lore(2).includes('主人 ↔ 牛'),'distance lore');assert(lore(44).includes('普攻剩余冷却'),'real cooldown lore');assert(lore(43).includes('目标'),'target lore');console.log('TRIPLE_CLICK_PASS');
 const old=lore(2);await cmd('/tp @e[tag=supercow_pet,limit=1,sort=nearest] 4.5 201 0.5',1350);assert(lore(2)!==old,'distance auto refresh'); console.log('LIVE_LORE_PASS');
 // Configure a deterministic normal-arrow-only combat test via the actual GUI.
 // Keep skill configuration intact until the forced-name check.
 await shut();await cmd('/supercow gui 1');await sleep(300);
 // Use each icon state rather than assume previous config; turn off enabled skills via all-off control.
 console.log('CONTROL_ITEMS',bot.currentWindow.slots.slice(36,45).map(x=>x?.displayName));await bot.clickWindow(7,0,0);await sleep(250);assert(lore(49).includes('跟随'),'operation feedback stays in lore');console.log('LORE_FEEDBACK_PASS');
 await shut();await cmd('/tp @e[tag=supercow_pet,limit=1,sort=nearest] 1.5 201 0.5');
 click(p);await sleep(1400);click(p);await sleep(250);click(p);await sleep(300);assert(!bot.currentWindow,'expired first click must not count');click(p);await sleep(350);assert(bot.currentWindow,'new sequence opens');await shut();console.log('CLICK_TIMEOUT_PASS');
 await cmd('/kill @e[tag=sc_ui_target]');await cmd('/summon husk 6 201 0.5 {NoAI:1b,PersistenceRequired:1b,Tags:["sc_ui_target"],Attributes:[{Name:"generic.max_health",Base:400.0}],Health:400.0f}');
 await cmd('/supercow gui 1',350);await bot.clickWindow(39,0,0);await sleep(200);await shut();
 await cmd('/supercow skill anvil_guillotine',350);console.log('CAST_FEEDBACK',msgs.slice(-4));assert(name(p).includes('铁砧')||name(p).includes('断头')||name(p).includes('闸刀'),'skill name shown '+name(p));console.log('SKILL_NAME_PASS',name(p));await sleep(2400);assert(!name(p).includes(' | ')&&!name(p).includes('§7|'),'skill name expires');console.log('NAME_RESTORE_PASS');
 // Verify every skill tooltip is a valid vanilla JSON component on all pages.
 let total=0;for(let page=1;page<=5;page++){await cmd('/supercow gui '+page,400);assert(bot.currentWindow,'page '+page);const slots=bot.currentWindow.slots.slice(9,36);for(let i=0;i<slots.length;i++){if(slots[i]&&['lime_dye','gray_dye'].includes(slots[i].name)){assert(lore(9+i).includes('▸'),'skill intro');total++}}console.log('GUI_PAGE',page);}
 assert(total===117,'all skill introductions');await cmd('/tp @e[tag=supercow_pet,limit=1,sort=nearest] 30.5 201 0.5',350);assert(bot.currentWindow,'remote control stays open');await bot.clickWindow(53,0,0);await sleep(350);assert(!bot.currentWindow,'close button deferred safely');await cmd('/tp @e[tag=supercow_pet,limit=1,sort=nearest] 1.5 201 0.5');console.log('GUI_PAGES_AND_CLOSE_PASS');
 // Only enable arrow using GUI controls to eliminate random specials.
 await cmd('/supercow gui 1',400);const w=bot.currentWindow;assert(w,'GUI available');
 await bot.clickWindow(40,0,0);await sleep(250);
 if(bot.currentWindow.slots[9].name!=='lime_dye'){await bot.clickWindow(9,0,0);await sleep(200);} await shut();
 await sleep(1800);await cmd('/tp @e[tag=sc_ui_target,limit=1] 3.5 201 0.5');await cmd('/tp @e[tag=supercow_pet,limit=1,sort=nearest] 1.5 201 0.5');
 const victim=Object.values(bot.entities).find(e=>e.name==='husk'&&e.position.distanceTo(bot.entity.position)<7);assert(victim,'target visible');const shots=[];let lastHealth=victim.metadata[8];const listener=packet=>{if(packet.entityId!==victim.id)return;for(const m of packet.metadata){if(m.key===8){if(m.value<lastHealth-.01){shots.push(Date.now());console.log('DAMAGE',lastHealth-m.value);}lastHealth=m.value;}}};bot._client.on('entity_metadata',listener);await cmd('/supercow mode aggressive',9200);await cmd('/supercow mode passive');bot._client.removeListener('entity_metadata',listener);
 const intervals=shots.slice(1).map((t,i)=>t-shots[i]);console.log('ACTUAL_HITS',JSON.stringify({count:shots.length,intervals}));assert(shots.length>=7,'normal attacks too sparse');const med=intervals.slice().sort((a,b)=>a-b)[Math.floor(intervals.length/2)];assert(med>=650&&med<=1200,'normal cadence incorrect '+med);
 await cmd('/kill @e[tag=sc_ui_target]');assert(!bars.some(x=>/距离.*格/.test(x)),'distance actionbar');console.log('UI_COMBAT_SMOKE_PASS');bot.quit();setTimeout(()=>process.exit(0),200);
 }catch(e){console.error('UI_COMBAT_SMOKE_FAIL',e.stack);bot.quit();setTimeout(()=>process.exit(1),200)}});
setTimeout(()=>{console.error('SMOKE_TIMEOUT');process.exit(2)},75000);

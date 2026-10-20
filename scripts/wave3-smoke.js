// TEST SERVER ONLY. Requires mineflayer@4 and an existing OP player with a SuperCow.
// Exercises 117 GUI intros, 30 forced casts and automatic entity cleanup.
// Run: NODE_PATH=/tmp/bot/node_modules node scripts/wave3-smoke.js
const mineflayer = require('mineflayer');
const assert = require('assert');
const bot = mineflayer.createBot({host:'127.0.0.1',port:Number(process.env.SC_TEST_PORT || 25566),username:process.env.SC_TEST_USER || 'TestBot',version:'1.16.5'});
const sleep = ms => new Promise(r=>setTimeout(r,ms));
const messages=[];
bot.on('message',m=>{const s=m.toString();messages.push(s); console.log('CHAT',s)});
bot.on('error',e=>{console.error(e);process.exit(1)});
const ids = 'anvil_guillotine sheep_rainbow_cannon tnt_hot_potato fishing_crane slime_pinball villager_auction end_rod_drill chicken_gatling boat_drift creeper_fakeout pufferfish_minefield bed_bounce_castle squid_ink_printer golem_chiropractor llama_spit_turret panda_bowling cat_keyboard_walker minecart_rollercoaster turtle_spinning_shell fox_berry_trap snowman_blender pig_rocket_express witch_cauldron_soup skeleton_jazz_band enderman_moving_company polar_bear_slap silverfish_tickle_pit dolphin_surfboard mooshroom_nuclear_stew bat_ufo_abduction'.split(' ');
async function command(s,ms=250){bot.chat(s);await sleep(ms)}
function readWindow(page){return new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(Error('GUI timeout '+page)),5000);bot.once('windowOpen',w=>{clearTimeout(timer);resolve(w)});bot.chat('/supercow gui '+page)})}
bot.once('spawn', async()=>{try{
 await sleep(1000);
 await command('/gamemode creative');
 await command('/supercow call');
 await command('/supercow mode passive');
 await command('/supercow quirk safe');
 let guiCount=0;
 for(let page=1;page<=5;page++){
   const w=await readWindow(page);await sleep(200);
   const slots=w.slots.slice(9,36).filter(s=>s && (s.name==='lime_dye'||s.name==='gray_dye'));
   for(const item of slots) assert(JSON.stringify(item.nbt).includes('▸'), 'Missing intro in GUI');
   guiCount+=slots.length;console.log('GUI_PAGE',page,'SKILLS',slots.length);
   bot.closeWindow(w);await sleep(300);
 }
 assert.equal(guiCount,117);
 let successes=0;
 for(const id of ids){
   await command('/kill @e[tag=wave3_test_target]');
   await command('/summon minecraft:zombie ~3 ~ ~ {NoAI:1b,PersistenceRequired:1b,Tags:["wave3_test_target"],Attributes:[{Name:"generic.max_health",Base:200.0}],Health:200.0f}');
   const start=messages.length;
   await command('/supercow skill '+id,2700);
   assert(messages.slice(start).some(m=>m.includes('释放技能')&&m.includes(id)), 'Cast rejected '+id);
   successes++;console.log('CAST_PASS',id);
 }
 await command('/kill @e[tag=wave3_test_target]');
 await sleep(6000);
 const start=messages.length;
 await command('/supercow skills',700);
 assert(messages.slice(start).some(m=>/活动技能实体：\s*0\s/.test(m)), 'Actors did not clean up');
 console.log('SMOKE_PASS',JSON.stringify({skills:guiCount,casts:successes,pages:5}));
 bot.quit();setTimeout(()=>process.exit(0),300);
}catch(e){console.error('SMOKE_FAIL',e.stack);bot.quit();setTimeout(()=>process.exit(1),300)}});
setTimeout(()=>{console.error('SMOKE_FAIL hard timeout');process.exit(1)},150000);

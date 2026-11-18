#!/usr/bin/env node
/* 「我」页面冒烟测试：不需要真机、不发真实网络请求
 * 用法：node tests/test_me_page.js [可选：被测 index.html 路径]
 *
 * 覆盖：
 *  - 真实 HTML 里必须存在「我」页容器（回归：曾因文件写入丢失导致点「我」整页空白）
 *  - 登录态渲染、本地整理（不写网络）
 *  - 写接口的 method / path / body / 鉴权头
 *  - 删除仓库的二次确认、上传时 sha 的获取与覆盖逻辑、UTF-8 base64 往返
 *
 * DOM 桩是「严格」的：getElementById 只认识静态 HTML 里出现过的 id，
 * 以及运行时 innerHTML 里注入过的 id；其余一律返回 null（和真实浏览器一致）。
 * 这样 HTML 少写一个 <section> 就会立刻被测出来，而不是被桩凭空造出来掩盖掉。
 */
'use strict';
var fs = require('fs');
var path = require('path');
var vm = require('vm');

var htmlPath = process.argv[2] || path.join(__dirname, '..', 'assets', 'index.html');
var html = fs.readFileSync(htmlPath, 'utf8');
var m = html.match(/<script>([\s\S]*?)<\/script>/);
if (!m) { console.error('找不到 <script> 段：' + htmlPath); process.exit(1); }
var src = m[1];

/* ---------------- 严格 DOM / fetch 桩 ---------------- */
var els = {};
var knownIds = Object.create(null);
function registerIds(text) {
  var re = /\bid="([^"]+)"/g, mm;
  while ((mm = re.exec(String(text)))) knownIds[mm[1]] = true;
}
registerIds(html);           // 静态 HTML 里真实存在的 id

function makeEl(id) {
  var e = {
    id: id, textContent: '', value: '', checked: false, className: '',
    style: {}, children: [],
    setAttribute: function () {}, getAttribute: function () { return null; },
    appendChild: function () {}, addEventListener: function () {},
    closest: function () { return null; }, focus: function () {}
  };
  var _html = '';
  Object.defineProperty(e, 'innerHTML', {
    get: function () { return _html; },
    set: function (v) { _html = String(v); registerIds(_html); }   // 注入的 HTML 会带来新 id
  });
  return e;
}
function forceEl(id) { if (!els[id]) els[id] = makeEl(id); return els[id]; }

// 真实 HTML 里的底部导航按钮，用于校验 setTab 的高亮切换
var navTabs = [];
(function () {
  var re = /data-tab="([^"]+)"/g, mm;
  while ((mm = re.exec(html))) navTabs.push(mm[1]);
})();

var stub = {
  document: {
    getElementById: function (id) { return knownIds[id] ? forceEl(id) : null; },  // 严格：未知 id 返回 null
    querySelector: function () { return null; },
    querySelectorAll: function (sel) {
      if (sel === 'nav button') {
        return navTabs.map(function (t) {
          return { getAttribute: function (k) { return k === 'data-tab' ? t : null; }, className: '' };
        });
      }
      return [];
    },
    addEventListener: function () {}
  },
  localStorage: (function () {
    var s = {};
    return {
      getItem: function (k) { return Object.prototype.hasOwnProperty.call(s, k) ? s[k] : null; },
      setItem: function (k, v) { s[k] = String(v); },
      removeItem: function (k) { delete s[k]; }
    };
  })(),
  console: console
};
stub.window = { onPickFile: null, scrollTo: function () {}, location: { href: '' } };

var calls = [];      // 所有 fetch 调用
var queue = [];      // 预置响应（先进先出）
function resp(status, obj, raw) {
  var text = raw !== undefined ? raw : JSON.stringify(obj === undefined ? {} : obj);
  return {
    ok: status >= 200 && status < 300,
    status: status,
    json: function () { return Promise.resolve(obj === undefined ? {} : obj); },
    text: function () { return Promise.resolve(text); }
  };
}
function stubFetch(url, opts) {
  calls.push({ url: url, opts: opts || {} });
  var r = queue.length ? queue.shift() : resp(200, {});
  return Promise.resolve(r);
}
stub.fetch = stubFetch;
stub.setTimeout = setTimeout;
stub.clearTimeout = clearTimeout;
stub.Promise = Promise;
stub.btoa = function (s) { return Buffer.from(s, 'binary').toString('base64'); };
stub.atob = function (s) { return Buffer.from(s, 'base64').toString('binary'); };
stub.escape = escape;
stub.unescape = unescape;
stub.AbortController = AbortController;
stub.URL = URL;

var ctx = vm.createContext(stub);
var repoSample = {
  id: 1, full_name: 'feiniu8/demo-app', name: 'demo-app',
  owner: { login: 'feiniu8', type: 'User' },
  description: '示例项目', stargazers_count: 12, forks_count: 3, language: 'Java',
  topics: ['android'], fork: false, private: false, archived: false,
  html_url: 'https://github.com/feiniu8/demo-app', default_branch: 'main',
  created_at: '2026-01-02T00:00:00Z', updated_at: '2026-11-01T00:00:00Z', pushed_at: '2026-11-10T00:00:00Z',
  homepage: '', size: 100
};

var pass = 0, fail = 0;
function check(name, cond, extra) {
  if (cond) { pass++; console.log('  ✓ ' + name); }
  else { fail++; console.log('  ✗ ' + name + (extra ? '  → ' + extra : '')); }
}
function run(code) { return vm.runInContext(code, ctx); }
function lastCall() { return calls[calls.length - 1] || {}; }
function tick(n) { return new Promise(function (r) { setTimeout(r, n || 30); }); }
// 测试侧写值用的“必须存在”的元素：不存在就记失败，避免对着 null 设值把用例蒙过去
function input(id) {
  if (!knownIds[id]) { check('元素 #' + id + ' 存在于 DOM', false, '需要先渲染出对应弹窗'); return null; }
  return forceEl(id);
}

/* ---------------- 用例 ---------------- */
var cases = [];

cases.push(['HTML 里真的有「我」页容器（点「我」不白屏的根）', function () {
  check('底部导航 6 个 tab 且含 me', navTabs.length === 6 && navTabs.indexOf('me') >= 0, navTabs.join(','));
  check('存在 <section class="page" id="page-me">',
    /<section class="page" id="page-me">/.test(html));
  check('page-me 里有 me-auth 容器', !!knownIds['me-auth']);
  check('page-me 里有 me-body 容器', !!knownIds['me-body']);
  check('me 的容器在 </main> 之前', html.indexOf('id="page-me"') < html.indexOf('</main>'));

  // 真正跑一遍切换：缺容器时这里会抛异常（和手机上一样）
  var threw = null;
  try { run('state.token="";lsDel("oauth_token");MS.anon="";MS.me=null;MS.repos=[];MS.loaded=false;setTab("me");'); }
  catch (e) { threw = e.message; }
  check('点「我」切换页面不抛异常', threw === null, threw);
  check('page-me 变为可见', (forceEl('page-me').className || '').indexOf('active') >= 0,
    'className=' + forceEl('page-me').className);
  check('page-search 变为隐藏', (forceEl('page-search').className || '').indexOf('active') < 0);
  check('未登录时「我」页显示登录入口',
    (forceEl('me-auth').innerHTML || '').indexOf('用 GitHub 账号登录') >= 0);
}]);

cases.push(['脚本可解析且新接口齐全', function () {
  var names = ['renderMe', 'loadMe', 'deviceLogin', 'pollDevice', 'saveMeToken', 'meLogout',
    'ghSend', 'meSaveDesc', 'meSaveHome', 'meArchive', 'meDoDelete', 'meCreateRepo',
    'meFiles', 'meSaveFile', 'meDoDeleteFile', 'meDoUpload', 'window.onPickFile', 'setTab'];
  var missing = names.filter(function (n) { return run('typeof ' + n) !== 'function'; });
  check('全部关键函数存在', missing.length === 0, '缺失: ' + missing.join(','));
  check('底部导航已注册 me 页', /me:'page-me'/.test(src));
}]);

cases.push(['未登录时显示登录页而不是仓库列表', function () {
  run('state.token="";lsDel("oauth_token");MS.anon="";MS.me=null;MS.repos=[];MS.loaded=false;renderMe();');
  var auth = forceEl('me-auth').innerHTML, body = forceEl('me-body').innerHTML;
  check('出现 GitHub 登录入口', auth.indexOf('用 GitHub 账号登录') >= 0);
  check('出现令牌兜底入口', auth.indexOf('用访问令牌登录') >= 0);
  check('未登录不渲染项目卡片', body.indexOf('整理') < 0);
}]);

cases.push(['已登录渲染个人主页卡片与整理视图', function () {
  run('state.token="ghp_test";lsDel("oauth_token");MS.me={login:"feiniu8",name:"Black",type:"User",avatar_url:"https://a/b.png",bio:"hi",followers:7,created_at:"2015-03-01T00:00:00Z"};' +
    'MS.orgs=["black-org"];MS.repos=[' + JSON.stringify(repoSample) + '].map(normalizeMine);MS.loaded=true;MS.error="";renderMe();');
  var auth = forceEl('me-auth').innerHTML, body = forceEl('me-body').innerHTML;
  check('显示账号名与登录名', auth.indexOf('Black') >= 0 && auth.indexOf('@feiniu8') >= 0);
  check('显示组织与 Star 统计', auth.indexOf('black-org') >= 0 && auth.indexOf('Star 合计') >= 0);
  check('项目卡片带整理/文件/上传入口', body.indexOf('meEditDialog(') >= 0 && body.indexOf('meFiles(') >= 0 && body.indexOf('meUploadDialog(') >= 0);
  check('提供真实写操作入口（更多操作）', body.indexOf('meMenu(') >= 0);
  check('有新建仓库入口', body.indexOf('新建仓库') >= 0);
}]);

cases.push(['本地整理：备注/分类/隐藏只写 localStorage，不发请求', function () {
  return tick().then(function () {
    var before = calls.length;
    run('setMeta("feiniu8/demo-app",{note:"面试作品集",cat:"web",hidden:false});');
    var html1 = run('mineCard(MS.repos[0])');
    check('备注出现在卡片上', html1.indexOf('面试作品集') >= 0);
    check('自定义分类生效', html1.indexOf('Web 前端') >= 0);
    check('整理过程没有调用任何接口', calls.length === before, '多发 ' + (calls.length - before) + ' 次');
    run('setMeta("feiniu8/demo-app",{hidden:true});');
    check('隐藏后默认列表看不到', run('meList().length') === 0);
    run('toggleMeHidden();');
    check('打开「含隐藏」后重新可见', run('meList().length') === 1);
    run('setMeta("feiniu8/demo-app",{hidden:false,cat:"auto",note:""});');
    check('清除整理后恢复自动分类', run('meCatOf(MS.repos[0])') === 'android');
    check('自定义排序可上下调', run('(function(){setMeSort("custom");moveMine("feiniu8/demo-app",-1);return meOrder().indexOf("feiniu8/demo-app");})()') >= 0);
  });
}]);

cases.push(['ghSend：PATCH 改描述带 method/path/body/鉴权头', function () {
  run('meDescDialog("feiniu8/demo-app");');            // 先真的把弹窗渲染出来
  return tick().then(function () {
    var box = input('gh-desc'); if (!box) return;
    box.value = '新的项目描述';
    calls.length = 0; queue.push(resp(200, { full_name: 'feiniu8/demo-app', description: '新的项目描述' }));
    run('meSaveDesc("feiniu8/demo-app");');
    return tick(60);
  }).then(function () {
    var c = lastCall();
    check('打到 api.github.com/repos/{owner}/{repo}', /api\.github\.com\/repos\/feiniu8\/demo-app$/.test(c.url || ''), c.url);
    check('method=PATCH', (c.opts || {}).method === 'PATCH');
    check('body 只带 description', JSON.parse(c.opts.body).description === '新的项目描述');
    check('带 Bearer 鉴权头', /^Bearer ghp_test$/.test((c.opts.headers || {}).Authorization || ''));
    check('本地缓存的描述同步更新', run('mineRepo("feiniu8/demo-app").description') === '新的项目描述');
  });
}]);

cases.push(['归档走 PATCH 且 archive=true', function () {
  queue.push(resp(200, {}));
  run('meArchive("feiniu8/demo-app",1);');
  return tick(60).then(function () {
    var c = lastCall();
    check('archive=true', JSON.parse(c.opts.body).archived === true);
    check('method=PATCH', (c.opts || {}).method === 'PATCH');
    run('exitAnon();');
  });
}]);

cases.push(['删除仓库：输入不一致时绝不发 DELETE', function () {
  calls.length = 0;
  run('meDeleteDialog("feiniu8/demo-app");');          // 先渲染确认弹窗
  return tick().then(function () {
    var box = input('del-confirm'); if (!box) return;
    box.value = 'wrong/name';
    run('meDoDelete("feiniu8/demo-app");');
    return tick(50);
  }).then(function () {
    check('名称不一致时没有任何请求', calls.length === 0, '实际 ' + calls.length + ' 次');
    queue.push(resp(204, undefined, ''));
    input('del-confirm').value = 'feiniu8/demo-app';
    run('meDoDelete("feiniu8/demo-app");');
    return tick(60);
  }).then(function () {
    var c = lastCall();
    check('名称一致时发出 DELETE', (c.opts || {}).method === 'DELETE' && /\/repos\/feiniu8\/demo-app$/.test(c.url || ''), c.url);
    check('本地列表移除该仓库', run('mineRepo("feiniu8/demo-app")') === null);
    run('MS.repos=[' + JSON.stringify(repoSample) + '].map(normalizeMine);lsSet("me_gone_v1","[]");');
  });
}]);

cases.push(['上传：已存在则带 sha 覆盖，不存在则新建', function () {
  calls.length = 0;
  run('MS.pick=null;meUploadDialog("feiniu8/demo-app","");');   // 先渲染上传面板
  return tick(80).then(function () {
    // 已存在：先查 contents 拿到 sha，再 PUT 带 sha
    queue.push(resp(200, { type: 'file', sha: 'SHA123', size: 10 }));
    queue.push(resp(201, { commit: { sha: 'C1' }, content: { sha: 'S1' } }));
    input('up-path').value = 'docs/readme.md';
    input('up-msg').value = 'docs: add';
    input('up-content').value = '你好，黑色闪电';
    run('MS.pick=null;meDoUpload("feiniu8/demo-app");');
    return tick(80);
  }).then(function () {
    var put = calls.filter(function (c) { return (c.opts || {}).method === 'PUT'; }).pop();
    check('查到已有文件后 PUT 带 sha', put && JSON.parse(put.opts.body).sha === 'SHA123');
    check('内容按 UTF-8 做 base64', Buffer.from(JSON.parse(put.opts.body).content, 'base64').toString('utf8') === '你好，黑色闪电');
    check('路径正确编码', /\/contents\/docs\/readme\.md$/.test(put.url || ''), put.url);

    calls.length = 0;
    queue.push(resp(404, { message: 'Not Found' }));   // 文件不存在
    queue.push(resp(201, { commit: { sha: 'C2' }, content: { sha: 'S2' } }));
    input('up-path').value = 'new.txt';
    input('up-content').value = 'abc';
    run('meDoUpload("feiniu8/demo-app");');
    return tick(80);
  }).then(function () {
    var put = calls.filter(function (c) { return (c.opts || {}).method === 'PUT'; }).pop();
    check('新文件不带 sha', put && put.opts.body.indexOf('sha') < 0);
    check('新文件仍是 PUT 提交', JSON.parse(put.opts.body).content === Buffer.from('abc').toString('base64'));
  });
}]);

cases.push(['本机文件上传回调与体积上限', function () {
  run('MS.pick=null;');
  run('window.onPickFile("big.bin","application/octet-stream","QUJD",4*1024*1024);');
  check('超过 3MB 的文件被拒绝', run('MS.pick') === null);
  run('window.onPickFile("note.txt","text/plain","5L2g5aW9",6);');
  var pick = run('({n:MS.pick.name,b:MS.pick.b64,s:MS.pick.size})');
  check('正常文件被接收并记录文件名', pick.n === 'note.txt' && pick.b === '5L2g5aW9' && pick.s === 6);
  run('window.onPickFile("","", "",0);');
  check('未选文件时清空选择', run('MS.pick') === null);
}]);

cases.push(['base64 往返 + 无 token 时拒绝写操作', function () {
  check('UTF-8 base64 往返一致', run('b64ToUtf8(utf8ToB64("黑色闪电 ⚡ ok"))') === '黑色闪电 ⚡ ok');
  run('state.token="";lsDel("oauth_token");');
  calls.length = 0;
  return run('ghSend("PATCH","/repos/a/b",{description:"x"}).then(function(){return "SHOULD_NOT";},function(e){return e.message;})')
    .then(function (msg) {
      check('未登录时写操作被直接拒绝', /登录/.test(String(msg)), String(msg));
      check('未登录时不发出写请求', calls.length === 0);
    });
}]);

/* ---------------- 执行 ---------------- */
console.log('「我」页面冒烟测试　' + htmlPath + '\n');
var idx = 0;
function next() {
  if (idx >= cases.length) {
    console.log('\n结果：' + pass + ' 通过 / ' + fail + ' 失败');
    process.exit(fail ? 1 : 0);
  }
  var item = cases[idx++];
  console.log('• ' + item[0]);
  var r;
  try { r = item[1](); } catch (e) { check('用例执行不抛异常', false, e.message); r = null; }
  Promise.resolve(r).catch(function (e) { check('用例 Promise 不失败', false, e.message); })
    .then(function () { return tick(10); }).then(next);
}
try { vm.runInContext(src, ctx); } catch (e) {
  console.error('脚本解析失败：' + e.message);
  process.exit(1);
}
next();

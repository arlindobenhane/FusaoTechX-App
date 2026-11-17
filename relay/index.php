<?php
/**
 * FusãoTechX Mz • Relay — página de entrada (studio.fusaotech.com).
 * O PC escreve o ID do telefone (9 dígitos, mostrado na app) e abre o painel.
 */
define('FTX_RELAY', 1);
require __DIR__ . '/lib.php';
$base = rtrim(str_replace('\\', '/', dirname((string)($_SERVER['SCRIPT_NAME'] ?? '/relay/index.php'))), '/');
$id = preg_replace('/\D/', '', (string)($_GET['id'] ?? ''));
if (relay_id_ok($id)) { header('Location: ' . $base . '/d/' . $id . '/'); exit; }
relay_purga();
header('Content-Type: text/html; charset=utf-8');
?><!doctype html>
<html lang="pt">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>FusãoTechX Studio · Ligar ao telefone</title>
<meta name="description" content="Use o seu telefone no PC a partir de qualquer rede: ecrã ao vivo, SMS, chamadas, notificações e ficheiros.">
<link rel="icon" href="https://fusaotech.com/assets/images/logos/favicon.ico">
<style>
:root{--bg:#070a12;--p:rgba(255,255,255,.04);--l:rgba(255,255,255,.09);--tx:#eef2ff;--mu:#94a3c4;--ac:#22d3ee;--grad:linear-gradient(135deg,#22d3ee 0%,#818cf8 55%,#c084fc 100%)}
*{box-sizing:border-box}
html,body{margin:0;min-height:100%;background:var(--bg);color:var(--tx);font:15px/1.55 "Segoe UI",Inter,system-ui,sans-serif}
body{background:radial-gradient(1000px 560px at 85% -10%,rgba(129,140,248,.18),transparent 60%),radial-gradient(800px 500px at -10% 110%,rgba(34,211,238,.12),transparent 60%),var(--bg);min-height:100vh;display:flex;flex-direction:column}
a{color:var(--ac)}
.wrap{flex:1;width:min(1080px,calc(100% - 32px));margin:0 auto;display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:48px;align-items:center;padding:48px 0}
.top{width:min(1080px,calc(100% - 32px));margin:0 auto;display:flex;align-items:center;gap:12px;padding:20px 0}
.top img{width:40px;height:40px;border-radius:11px}.top b{font-size:16px}.top small{display:block;color:var(--mu);font-size:12px}
h1{font-size:clamp(32px,5vw,50px);line-height:1.06;letter-spacing:-.03em;margin:0 0 14px}
.g{background:var(--grad);-webkit-background-clip:text;background-clip:text;color:transparent}
.lead{color:#c5d0e2;font-size:17px;max-width:520px;margin:0 0 22px}
.feat{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;max-width:520px}
.feat div{padding:12px 14px;border-radius:14px;background:var(--p);border:1px solid var(--l);font-size:13.5px;color:#c5d0e2}
.card{padding:30px;border-radius:26px;background:linear-gradient(160deg,rgba(20,24,52,.92),rgba(9,11,26,.92));border:1px solid rgba(255,255,255,.12);box-shadow:0 30px 80px rgba(0,0,0,.45)}
.card h2{margin:0 0 4px;font-size:21px}.card p{margin:0 0 18px;color:var(--mu);font-size:14px}
label{display:block;font-size:12.5px;font-weight:700;color:var(--mu);text-transform:uppercase;letter-spacing:.08em;margin-bottom:8px}
.id{width:100%;padding:16px;border-radius:16px;border:1px solid rgba(255,255,255,.14);background:rgba(0,0,0,.35);color:#fff;font:700 30px/1 "Segoe UI",system-ui,monospace;letter-spacing:.14em;text-align:center;outline:none;transition:border .15s,box-shadow .15s}
.id:focus{border-color:#818cf8;box-shadow:0 0 0 4px rgba(129,140,248,.18)}
.btn{display:flex;align-items:center;justify-content:center;gap:10px;width:100%;margin-top:14px;padding:15px;border:0;border-radius:15px;background:var(--grad);color:#06121f;font:800 16px "Segoe UI",system-ui,sans-serif;cursor:pointer;box-shadow:0 12px 32px rgba(129,140,248,.35);transition:transform .15s}
.btn:hover{transform:translateY(-2px)}.btn:disabled{opacity:.5;cursor:not-allowed;transform:none}
.err{min-height:20px;margin-top:10px;color:#fb7185;font-size:13.5px;text-align:center}
.steps{margin:22px 0 0;padding:0;list-style:none;display:grid;gap:10px;counter-reset:s}
.steps li{display:flex;gap:12px;align-items:flex-start;color:#c5d0e2;font-size:14px}
.steps li::before{counter-increment:s;content:counter(s);flex:0 0 26px;height:26px;border-radius:8px;display:grid;place-items:center;font-weight:800;font-size:13px;background:rgba(129,140,248,.2);color:#c7d2fe}
.recent{margin-top:18px;display:flex;flex-wrap:wrap;gap:8px}
.recent a{padding:7px 12px;border-radius:99px;background:var(--p);border:1px solid var(--l);color:#c5d0e2;text-decoration:none;font-size:13px}
.recent a:hover{border-color:var(--ac);color:#fff}
.dl{display:inline-flex;gap:8px;align-items:center;margin-top:20px;font-weight:700;text-decoration:none}
footer{text-align:center;color:var(--mu);font-size:12.5px;padding:22px}
@media(max-width:860px){.wrap{grid-template-columns:minmax(0,1fr);gap:28px;padding:20px 0 40px}.feat{grid-template-columns:minmax(0,1fr)}}
</style>
</head>
<body>
<header class="top"><img src="https://fusaotech.com/assets/images/logos/android-chrome-192x192.png" alt=""><div><b>FusãoTechX Studio</b><small>O seu telefone no PC</small></div></header>
<main class="wrap">
    <section>
        <h1>O seu telefone no PC, <span class="g">em qualquer rede</span></h1>
        <p class="lead">Telefone nos dados móveis e PC noutra rede? Sem problema. Veja e controle o ecrã, envie SMS, faça chamadas e transfira ficheiros pelo navegador.</p>
        <div class="feat">
            <div>📱 Ecrã ao vivo com rato e teclado</div>
            <div>💬 SMS e WhatsApp a partir do PC</div>
            <div>🔔 Notificações em tempo real</div>
            <div>📁 Ficheiros nos dois sentidos</div>
        </div>
        <a class="dl" href="https://studio.fusaotech.com/app">⬇️ Descarregar a app Android (APK)</a>
    </section>
    <section class="card">
        <h2>Ligar ao telefone</h2>
        <p>Escreva o ID que aparece na app FusãoTechX, em «Ligação remota».</p>
        <form id="f" autocomplete="off">
            <label for="id">ID do telefone</label>
            <input class="id" id="id" inputmode="numeric" maxlength="11" placeholder="000 000 000" aria-describedby="err" autofocus>
            <button class="btn" id="go" disabled>Ligar →</button>
            <div class="err" id="err" role="alert"></div>
        </form>
        <div class="recent" id="recent"></div>
        <ol class="steps">
            <li>No telefone, abra a app e ative «Ligação remota».</li>
            <li>Toque em «LIGAR» e aceite a partilha do ecrã.</li>
            <li>Aqui no PC, escreva o ID e depois o código de acesso.</li>
        </ol>
    </section>
</main>
<footer>FusãoTechX Mz · +258 83 333 7906 · <a href="https://fusaotech.com/">fusaotech.com</a></footer>
<script>
(function () {
    var inp = document.getElementById('id'), go = document.getElementById('go'), err = document.getElementById('err');
    var BASE = <?php echo json_encode($base); ?>;
    function digitos() { return inp.value.replace(/\D/g, '').slice(0, 9); }
    function fmt() { var d = digitos(); inp.value = d.replace(/(\d{3})(?=\d)/g, '$1 '); go.disabled = d.length !== 9; err.textContent = ''; }
    inp.addEventListener('input', fmt);
    document.getElementById('f').addEventListener('submit', function (e) {
        e.preventDefault();
        var d = digitos();
        if (d.length !== 9) { err.textContent = 'O ID tem 9 dígitos.'; return; }
        try { var l = JSON.parse(localStorage.getItem('ftxRelayIds') || '[]').filter(function (x) { return x !== d; }); l.unshift(d); localStorage.setItem('ftxRelayIds', JSON.stringify(l.slice(0, 5))); } catch (x) {}
        location.href = BASE + '/d/' + d + '/';
    });
    try {
        var l = JSON.parse(localStorage.getItem('ftxRelayIds') || '[]'), box = document.getElementById('recent');
        l.forEach(function (d) { var a = document.createElement('a'); a.href = BASE + '/d/' + d + '/'; a.textContent = '📱 ' + d.replace(/(\d{3})(?=\d)/g, '$1 '); box.appendChild(a); });
    } catch (x) {}
})();
</script>
</body>
</html>

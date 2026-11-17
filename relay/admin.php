<?php
/**
 * FusãoTechX Mz • Painel de administração (studio.fusaotech.com/admin).
 * O administrador gere os aparelhos: licenças (dias/meses/anos/ilimitado), bloqueio e rótulos.
 * Protegido por conta (utilizador + palavra-passe). Sem base de dados — tudo em data/.
 */
define('FTX_RELAY', 1);
require __DIR__ . '/lib.php';
admin_sessao_iniciar();
$h = 'admin_h';
$base = rtrim(str_replace('\\', '/', dirname((string)($_SERVER['SCRIPT_NAME'] ?? '/admin.php'))), '/');
if (empty($_SESSION['csrf'])) $_SESSION['csrf'] = bin2hex(random_bytes(16));
$csrf = $_SESSION['csrf'];
$erro = ''; $ok = '';

// ---- Ações (POST) ----
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    if (!hash_equals($csrf, (string)($_POST['csrf'] ?? ''))) { http_response_code(400); exit('CSRF'); }
    $acao = (string)($_POST['acao'] ?? '');

    if ($acao === 'setup' && !admin_existe()) {
        $u = trim((string)($_POST['user'] ?? '')); $p = (string)($_POST['pass'] ?? '');
        if (strlen($u) < 3 || strlen($p) < 6) $erro = 'Utilizador (3+) e palavra-passe (6+) obrigatórios.';
        else { $c = cfg_get(); $c['user'] = $u; $c['hash'] = password_hash($p, PASSWORD_DEFAULT); $c['criado'] = time(); cfg_set($c); $_SESSION['ftx_admin'] = $u; $ok = 'Conta criada.'; }
    } elseif ($acao === 'login') {
        $c = cfg_get();
        if ($c['user'] && password_verify((string)($_POST['pass'] ?? ''), (string)$c['hash']) && hash_equals((string)$c['user'], trim((string)($_POST['user'] ?? '')))) {
            session_regenerate_id(true); $_SESSION['ftx_admin'] = $c['user'];
        } else { sleep(1); $erro = 'Utilizador ou palavra-passe errados.'; }
    } elseif ($acao === 'logout') {
        $_SESSION = []; session_destroy(); header('Location: ' . $base . '/admin'); exit;
    } elseif (admin_logado()) {
        $id = preg_replace('/\D/', '', (string)($_POST['id'] ?? ''));
        if ($acao === 'licenca' && relay_id_ok($id)) {
            $l = lic_get($id);
            $l['nome'] = mb_substr(trim((string)($_POST['nome'] ?? '')), 0, 60);
            $l['nota'] = mb_substr(trim((string)($_POST['nota'] ?? '')), 0, 200);
            if (!$l['criado']) { $l['criado'] = time(); $l['por'] = $_SESSION['ftx_admin']; }
            $plano = (string)($_POST['plano'] ?? '');
            $agora = time(); $desde = max($agora, (int)($l['ate'] ?? 0));
            $dias = ['7d' => 7, '30d' => 30, '90d' => 90, '180d' => 180, '365d' => 365];
            if ($plano === 'ilimitado') $l['ate'] = 0;
            elseif ($plano === 'custom') { $t = strtotime((string)($_POST['data'] ?? '')); if ($t) $l['ate'] = $t; }
            elseif (isset($dias[$plano])) $l['ate'] = $desde + $dias[$plano] * 86400;
            if ($l['estado'] === '' || $l['estado'] === 'bloqueado') $l['estado'] = 'ativo';
            lic_set($id, $l); $ok = 'Licença de ' . $id . ' atualizada.';
        } elseif ($acao === 'bloquear' && relay_id_ok($id)) {
            $l = lic_get($id); $l['estado'] = 'bloqueado'; if (!$l['criado']) { $l['criado'] = time(); $l['por'] = $_SESSION['ftx_admin']; } lic_set($id, $l); $ok = 'Aparelho ' . $id . ' bloqueado.';
        } elseif ($acao === 'desbloquear' && relay_id_ok($id)) {
            $l = lic_get($id); $l['estado'] = 'ativo'; lic_set($id, $l); $ok = 'Aparelho ' . $id . ' desbloqueado.';
        } elseif ($acao === 'apagar' && relay_id_ok($id)) {
            $dd = RELAY_DATA . '/' . $id;
            if (is_dir($dd)) { $it = new RecursiveIteratorIterator(new RecursiveDirectoryIterator($dd, FilesystemIterator::SKIP_DOTS), RecursiveIteratorIterator::CHILD_FIRST); foreach ($it as $f) $f->isDir() ? @rmdir($f->getPathname()) : @unlink($f->getPathname()); @rmdir($dd); }
            $ok = 'Aparelho ' . $id . ' removido.';
        } elseif ($acao === 'politica') {
            $c = cfg_get(); $c['politica_novos'] = ($_POST['politica'] ?? 'trial') === 'bloquear' ? 'bloquear' : 'trial';
            $c['trial_dias'] = max(1, min(60, (int)($_POST['trial_dias'] ?? 7))); cfg_set($c); $ok = 'Política guardada.';
        } elseif ($acao === 'senha') {
            $c = cfg_get();
            if (!password_verify((string)($_POST['atual'] ?? ''), (string)$c['hash'])) $erro = 'Palavra-passe atual errada.';
            elseif (strlen((string)($_POST['nova'] ?? '')) < 6) $erro = 'A nova deve ter 6+ caracteres.';
            else { $c['hash'] = password_hash((string)$_POST['nova'], PASSWORD_DEFAULT); cfg_set($c); $ok = 'Palavra-passe alterada.'; }
        }
    }
}

header('Content-Type: text/html; charset=utf-8');
$logado = admin_logado();
$temAdmin = admin_existe();
function dur_txt($ate) { if ($ate === 0) return 'Ilimitado'; if (!$ate) return '—'; $dias = ceil(($ate - time()) / 86400); return $dias < 0 ? 'Expirou há ' . (-$dias) . 'd' : ($dias . ' dia' . ($dias == 1 ? '' : 's') . ' · ' . date('d/m/Y', $ate)); }
?><!doctype html>
<html lang="pt" data-theme="escuro">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Admin · FusãoTechX Studio</title>
<link rel="icon" href="https://fusaotech.com/assets/images/logos/favicon.ico">
<style>
:root{--bg:#070a12;--card:rgba(255,255,255,.04);--card2:rgba(255,255,255,.07);--line:rgba(255,255,255,.1);--line2:rgba(255,255,255,.17);--tx:#eef2ff;--mu:#94a3c4;--ac:#22d3ee;--ac2:#818cf8;--ok:#34d399;--wa:#fbbf24;--er:#fb7185;--grad:linear-gradient(135deg,#22d3ee,#818cf8 55%,#c084fc);--font:"Segoe UI",Inter,system-ui,sans-serif}
*{box-sizing:border-box}
body{margin:0;min-height:100vh;background:radial-gradient(900px 500px at 85% -10%,rgba(129,140,248,.16),transparent 60%),var(--bg);color:var(--tx);font:15px/1.5 var(--font)}
a{color:var(--ac)}
input,select{font:inherit;color:var(--tx);background:rgba(0,0,0,.3);border:1px solid var(--line);border-radius:10px;padding:9px 11px;outline:none;width:100%}
input:focus,select:focus{border-color:var(--ac2)}
.wrap{max-width:1180px;margin:0 auto;padding:18px}
.top{display:flex;align-items:center;gap:12px;padding:14px 0 18px;border-bottom:1px solid var(--line);margin-bottom:18px;flex-wrap:wrap}
.top img{width:40px;height:40px;border-radius:11px}.top b{font-size:17px}.top small{display:block;color:var(--mu);font-size:12px}
.btn{display:inline-flex;align-items:center;gap:7px;padding:9px 14px;border-radius:10px;border:1px solid var(--line);background:var(--card2);color:var(--tx);font:inherit;font-weight:600;cursor:pointer;text-decoration:none}
.btn:hover{border-color:var(--line2)}.btn.p{background:var(--grad);color:#06121f;border:0}.btn.er{color:#fecdd3;border-color:rgba(251,113,133,.4);background:rgba(251,113,133,.12)}.btn.ok{color:#a7f3d0;border-color:rgba(52,211,153,.4);background:rgba(52,211,153,.12)}
.btn.sm{padding:6px 10px;font-size:13px}
.card{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:18px 20px;margin-bottom:16px}
.card h2{margin:0 0 14px;font-size:16px}
.alert{padding:12px 15px;border-radius:12px;margin-bottom:16px;font-weight:600}
.alert.er{background:rgba(251,113,133,.12);border:1px solid rgba(251,113,133,.35);color:#fecdd3}
.alert.ok{background:rgba(52,211,153,.12);border:1px solid rgba(52,211,153,.35);color:#a7f3d0}
.kpis{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;margin-bottom:16px}
.kpi{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:14px 16px}
.kpi b{font-size:24px;display:block}.kpi span{color:var(--mu);font-size:12.5px}
.dev{border:1px solid var(--line);border-radius:14px;padding:14px;margin-bottom:12px;background:var(--card)}
.dev .hd{display:flex;align-items:center;gap:12px;flex-wrap:wrap}
.dev .idp{font:800 16px/1 "Segoe UI",monospace;letter-spacing:.1em;padding:6px 10px;border-radius:9px;background:var(--card2);border:1px solid var(--line)}
.tag{font-size:11.5px;font-weight:800;padding:3px 9px;border-radius:99px}
.tag.on{background:rgba(52,211,153,.18);color:var(--ok)}.tag.off{background:rgba(148,163,184,.18);color:var(--mu)}
.tag.bl{background:rgba(251,113,133,.18);color:var(--er)}.tag.ex{background:rgba(251,191,36,.18);color:var(--wa)}.tag.tr{background:rgba(129,140,248,.18);color:#c7d2fe}
.dev form{display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin-top:12px;padding-top:12px;border-top:1px solid var(--line)}
.dev .meta{color:var(--mu);font-size:12.5px;margin-top:4px}
.grid2{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}
.login{max-width:380px;margin:8vh auto;text-align:center}
.login .card{padding:30px 26px}.login img{width:72px;height:72px;border-radius:18px;margin-bottom:10px}
.login h1{font-size:22px;margin:4px 0 2px}.login p{color:var(--mu);margin:0 0 18px}
label.l{display:block;text-align:left;font-size:12.5px;color:var(--mu);font-weight:700;margin:10px 0 5px}
.flex{display:flex;gap:8px;flex-wrap:wrap;align-items:center}
@media(max-width:760px){.kpis{grid-template-columns:repeat(2,minmax(0,1fr))}.grid2{grid-template-columns:minmax(0,1fr)}.dev form{flex-direction:column;align-items:stretch}}
</style>
</head>
<body>
<div class="wrap">
<?php if (!$logado): ?>
  <div class="login">
    <div class="card">
      <img src="https://fusaotech.com/assets/images/logos/android-chrome-192x192.png" alt="">
      <h1>FusãoTechX Studio</h1>
      <p>Administração</p>
      <?php if ($erro): ?><div class="alert er"><?php echo $h($erro); ?></div><?php endif; ?>
      <?php if (!$temAdmin): ?>
        <form method="post"><input type="hidden" name="csrf" value="<?php echo $h($csrf); ?>"><input type="hidden" name="acao" value="setup">
          <p style="color:var(--wa);font-size:13px">Primeira vez: crie a conta de administrador.</p>
          <label class="l">Utilizador</label><input name="user" autocomplete="username" required>
          <label class="l">Palavra-passe</label><input name="pass" type="password" autocomplete="new-password" required>
          <button class="btn p" style="width:100%;margin-top:16px">Criar conta</button>
        </form>
      <?php else: ?>
        <form method="post"><input type="hidden" name="csrf" value="<?php echo $h($csrf); ?>"><input type="hidden" name="acao" value="login">
          <label class="l">Utilizador</label><input name="user" autocomplete="username" required autofocus>
          <label class="l">Palavra-passe</label><input name="pass" type="password" autocomplete="current-password" required>
          <button class="btn p" style="width:100%;margin-top:16px">Entrar</button>
        </form>
      <?php endif; ?>
    </div>
    <p style="color:var(--mu);font-size:12px">FusãoTechX Mz · fusaotech.com</p>
  </div>
<?php else:
  $todos = lic_todos();
  $online = count(array_filter($todos, fn($d) => $d['online']));
  $bloq = count(array_filter($todos, fn($d) => $d['estado'] === 'bloqueado'));
  $ativos = count(array_filter($todos, fn($d) => $d['estado'] === 'ativo' && (!$d['ate'] || $d['ate'] > time())));
  $cfg = cfg_get();
?>
  <div class="top">
    <img src="https://fusaotech.com/assets/images/logos/android-chrome-192x192.png" alt="">
    <div style="flex:1"><b>FusãoTechX Studio · Admin</b><small>Sessão: <?php echo $h($_SESSION['ftx_admin']); ?></small></div>
    <a class="btn sm" href="https://studio.fusaotech.com/" target="_blank">Painel do telefone ↗</a>
    <form method="post" style="display:inline"><input type="hidden" name="csrf" value="<?php echo $h($csrf); ?>"><input type="hidden" name="acao" value="logout"><button class="btn sm">Sair</button></form>
  </div>
  <?php if ($ok): ?><div class="alert ok"><?php echo $h($ok); ?></div><?php endif; ?>
  <?php if ($erro): ?><div class="alert er"><?php echo $h($erro); ?></div><?php endif; ?>

  <div class="kpis">
    <div class="kpi"><b><?php echo count($todos); ?></b><span>Aparelhos</span></div>
    <div class="kpi"><b style="color:var(--ok)"><?php echo $online; ?></b><span>Ligados agora</span></div>
    <div class="kpi"><b><?php echo $ativos; ?></b><span>Licenças ativas</span></div>
    <div class="kpi"><b style="color:var(--er)"><?php echo $bloq; ?></b><span>Bloqueados</span></div>
  </div>

  <div class="card">
    <h2>📱 Aparelhos</h2>
    <?php if (!$todos): ?>
      <p style="color:var(--mu)">Ainda não há aparelhos. Instale a app num telefone, ative «Ligação remota» e o ID aparece aqui.</p>
    <?php endif; ?>
    <?php foreach ($todos as $d): $est = $d['estado']; $exp = $d['ate'] && $d['ate'] < time();
      $tag = $est === 'bloqueado' ? ['bl', '🚫 Bloqueado'] : ($exp ? ['ex', '⌛ Expirado'] : ($est === 'novo' ? ['tr', '✨ Novo'] : ['on', '✅ Ativo'])); ?>
      <div class="dev">
        <div class="hd">
          <span class="idp"><?php echo $h(substr($d['id'],0,3).' '.substr($d['id'],3,3).' '.substr($d['id'],6)); ?></span>
          <span class="tag <?php echo $tag[0]; ?>"><?php echo $tag[1]; ?></span>
          <span class="tag <?php echo $d['online'] ? 'on' : 'off'; ?>"><?php echo $d['online'] ? '🟢 Ligado' : '⚪ Desligado'; ?></span>
          <div style="flex:1;min-width:140px">
            <b><?php echo $h($d['nome'] ?: 'Sem rótulo'); ?></b>
            <div class="meta"><?php echo $h($d['aparelho'] ?: 'aparelho desconhecido'); ?> · validade: <?php echo $h(dur_txt($d['ate'])); ?><?php echo $d['visto'] ? ' · visto ' . $h(date('d/m H:i', $d['visto'])) : ''; ?></div>
            <?php if ($d['nota']): ?><div class="meta">📝 <?php echo $h($d['nota']); ?></div><?php endif; ?>
          </div>
          <?php if ($est === 'bloqueado'): ?>
            <form method="post"><input type="hidden" name="csrf" value="<?php echo $h($csrf); ?>"><input type="hidden" name="acao" value="desbloquear"><input type="hidden" name="id" value="<?php echo $h($d['id']); ?>"><button class="btn sm ok">Desbloquear</button></form>
          <?php else: ?>
            <form method="post" onsubmit="return confirm('Bloquear este aparelho? Deixa de poder usar o serviço.')"><input type="hidden" name="csrf" value="<?php echo $h($csrf); ?>"><input type="hidden" name="acao" value="bloquear"><input type="hidden" name="id" value="<?php echo $h($d['id']); ?>"><button class="btn sm er">Bloquear</button></form>
          <?php endif; ?>
          <form method="post" onsubmit="return confirm('Apagar este aparelho da lista? Não desinstala a app, só remove daqui.')"><input type="hidden" name="csrf" value="<?php echo $h($csrf); ?>"><input type="hidden" name="acao" value="apagar"><input type="hidden" name="id" value="<?php echo $h($d['id']); ?>"><button class="btn sm">🗑️</button></form>
        </div>
        <form method="post">
          <input type="hidden" name="csrf" value="<?php echo $h($csrf); ?>"><input type="hidden" name="acao" value="licenca"><input type="hidden" name="id" value="<?php echo $h($d['id']); ?>">
          <input name="nome" placeholder="Rótulo (ex.: Polícia · João)" value="<?php echo $h($d['nome']); ?>" style="max-width:220px">
          <select name="plano" style="max-width:170px">
            <option value="">Manter validade</option>
            <option value="7d">+ 7 dias</option><option value="30d">+ 30 dias</option><option value="90d">+ 90 dias</option>
            <option value="180d">+ 6 meses</option><option value="365d">+ 1 ano</option>
            <option value="ilimitado">Ilimitado</option><option value="custom">Até data…</option>
          </select>
          <input type="date" name="data" style="max-width:160px" title="Usado com «Até data…»">
          <input name="nota" placeholder="Nota interna" value="<?php echo $h($d['nota']); ?>" style="max-width:200px">
          <button class="btn p sm">💾 Guardar</button>
        </form>
      </div>
    <?php endforeach; ?>
  </div>

  <div class="grid2">
    <div class="card">
      <h2>⚙️ Aparelhos novos</h2>
      <form method="post" class="flex">
        <input type="hidden" name="csrf" value="<?php echo $h($csrf); ?>"><input type="hidden" name="acao" value="politica">
        <select name="politica" style="max-width:260px">
          <option value="trial" <?php echo $cfg['politica_novos'] === 'trial' ? 'selected' : ''; ?>>Dar avaliação automática</option>
          <option value="bloquear" <?php echo $cfg['politica_novos'] === 'bloquear' ? 'selected' : ''; ?>>Exigir a minha autorização</option>
        </select>
        <span style="color:var(--mu);font-size:13px">Avaliação:</span>
        <input type="number" name="trial_dias" min="1" max="60" value="<?php echo (int)$cfg['trial_dias']; ?>" style="max-width:80px"> <span style="color:var(--mu);font-size:13px">dias</span>
        <button class="btn p sm">Guardar</button>
      </form>
      <p style="color:var(--mu);font-size:12.5px;margin:12px 0 0">Com «exigir autorização», um telefone novo fica à espera até você lhe dar licença aqui.</p>
    </div>
    <div class="card">
      <h2>🔑 Minha palavra-passe</h2>
      <form method="post">
        <input type="hidden" name="csrf" value="<?php echo $h($csrf); ?>"><input type="hidden" name="acao" value="senha">
        <label class="l">Atual</label><input name="atual" type="password" autocomplete="current-password" required>
        <label class="l">Nova (6+)</label><input name="nova" type="password" autocomplete="new-password" required>
        <button class="btn p sm" style="margin-top:12px">Alterar</button>
      </form>
    </div>
  </div>
  <p style="color:var(--mu);font-size:12px;text-align:center;padding:10px">FusãoTechX Mz · +258 83 333 7906 · fusaotech.com</p>
<?php endif; ?>
</div>
</body>
</html>

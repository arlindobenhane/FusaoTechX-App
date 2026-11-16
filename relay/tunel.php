<?php
/**
 * FusãoTechX Mz • Relay — lado do PC.
 * fusaotech.com/relay/d/<ID>/<caminho> → o pedido fica na fila do telefone <ID>,
 * o telefone executa-o no seu próprio servidor (o mesmo da rede local) e devolve a resposta.
 * O ecrã ao vivo não passa pela fila: o telefone envia a última imagem e o PC vai buscá-la em /shot.
 */
define('FTX_RELAY', 1);
require __DIR__ . '/lib.php';

$base = rtrim(str_replace('\\', '/', dirname((string)($_SERVER['SCRIPT_NAME'] ?? '/relay/tunel.php'))), '/');
$uri = (string)($_SERVER['REQUEST_URI'] ?? '');
$qpos = strpos($uri, '?');
$caminho = $qpos === false ? $uri : substr($uri, 0, $qpos);
$query = $qpos === false ? '' : substr($uri, $qpos + 1);
if (!preg_match('#^' . preg_quote($base, '#') . '/d/([0-9]{9})(/(.*))?$#', rawurldecode($caminho), $mm)) relay_json(['erro' => 'id'], 404);
$id = $mm[1];
if (!isset($mm[2])) { header('Location: ' . $base . '/d/' . $id . '/'); exit; }
$p = (string)($mm[3] ?? '');
$m = relay_meta($id);

// Telefone desligado (ou ID que nunca se ligou).
if (!relay_online($m)) {
    if ($p === '') {
        http_response_code(200);
        header('Content-Type: text/html; charset=utf-8');
        $idf = substr($id, 0, 3) . ' ' . substr($id, 3, 3) . ' ' . substr($id, 6);
        $nome = htmlspecialchars((string)($m['nome'] ?? ''), ENT_QUOTES, 'UTF-8');
        echo '<!doctype html><html lang="pt"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="refresh" content="5"><title>A aguardar o telefone · FusãoTechX</title>'
           . '<style>body{margin:0;min-height:100vh;display:grid;place-items:center;background:#070a12;color:#eef2ff;font:15px/1.5 "Segoe UI",system-ui,sans-serif;text-align:center;padding:24px}'
           . '.b{max-width:420px}.r{width:64px;height:64px;margin:0 auto 18px;border-radius:50%;border:4px solid rgba(255,255,255,.1);border-top-color:#22d3ee;animation:s 1s linear infinite}@keyframes s{to{transform:rotate(360deg)}}'
           . 'h1{font-size:22px;margin:0 0 6px}p{color:#94a3c4;margin:6px 0}b{color:#22d3ee}a{color:#818cf8}</style></head><body><div class="b"><div class="r"></div>'
           . '<h1>A aguardar o telefone' . ($nome !== '' ? ' «' . $nome . '»' : '') . '</h1><p>ID <b>' . $idf . '</b></p>'
           . '<p>No telefone, abra a app <b>FusãoTechX</b>, ative <b>Ligação remota</b> e toque em <b>LIGAR</b>. Esta página atualiza sozinha.</p>'
           . '<p style="margin-top:22px"><a href="' . htmlspecialchars($base, ENT_QUOTES) . '/">← Outro telefone</a></p></div></body></html>';
        exit;
    }
    relay_json(['erro' => 'offline'], 503);
}
$d = relay_dir($id);

// O código de acesso é confirmado aqui (bloqueio por IP) e outra vez no telefone.
$publico = in_array($p, ['', 'login', 'logo', 'favicon.ico'], true);
if ($p === 'login' && relay_bloqueado($id)) relay_json(['ok' => false, 'erro' => 'bloqueado'], 429);
if (!$publico) {
    if (relay_bloqueado($id)) relay_json(['erro' => 'bloqueado'], 429);
    parse_str($query, $qq);
    $k = (string)($qq['k'] ?? ($_SERVER['HTTP_X_KEY'] ?? ''));
    if (!empty($m['ch'])) {
        if (!relay_codigo_ok($m, $k)) { relay_falha($id, true); relay_json(['erro' => 'codigo'], 401); }
        relay_falha($id, false);
    }
}

// Ecrã ao vivo: última imagem enviada pelo telefone (espera até 3 s por uma mais nova).
if ($p === 'shot') {
    touch($d . '/want');
    session_write_close();
    $desde = (int)($_GET['since'] ?? 0);
    $fim = microtime(true) + 3;
    do {
        clearstatcache();
        $seq = (int)@file_get_contents($d . '/frame.seq');
        if ($seq > $desde && is_file($d . '/frame.jpg')) {
            $img = @file_get_contents($d . '/frame.jpg');
            if ($img !== false && strlen($img) > 100) {
                header('Content-Type: image/jpeg');
                header('Cache-Control: no-store');
                header('X-Seq: ' . $seq);
                header('Content-Length: ' . strlen($img));
                echo $img;
                exit;
            }
        }
        if (connection_aborted()) exit;
        usleep(40000);
    } while (microtime(true) < $fim);
    http_response_code(204);
    header('X-Seq: ' . $desde);
    exit;
}
if ($p === 'stream') relay_json(['erro' => 'use /shot'], 404);

// Qualquer outro pedido: fila → telefone → resposta.
session_write_close();
$rid = bin2hex(random_bytes(8));
$metodo = strtoupper((string)($_SERVER['REQUEST_METHOD'] ?? 'GET')) === 'POST' ? 'POST' : 'GET';
$tem_corpo = false;
if ($metodo === 'POST') {
    $in = fopen('php://input', 'rb'); $out = fopen($d . '/req/' . $rid . '.body', 'wb');
    $n = stream_copy_to_stream($in, $out, RELAY_MAX_BODY);
    fclose($in); fclose($out);
    $tem_corpo = $n > 0;
    if (!$tem_corpo) @unlink($d . '/req/' . $rid . '.body');
}
$pedido = ['rid' => $rid, 'm' => $metodo, 'p' => '/' . $p . ($query !== '' ? '?' . $query : ''),
           't' => substr((string)($_SERVER['CONTENT_TYPE'] ?? ''), 0, 120), 'b' => $tem_corpo];
file_put_contents($d . '/req/' . $rid . '.tmpj', json_encode($pedido, JSON_UNESCAPED_SLASHES));
rename($d . '/req/' . $rid . '.tmpj', $d . '/req/' . $rid . '.json');

$fim = microtime(true) + RELAY_REQ_TIMEOUT_S;
$rj = $d . '/resp/' . $rid . '.json';
do {
    clearstatcache(true, $rj);
    if (is_file($rj)) {
        $info = json_decode((string)@file_get_contents($rj), true) ?: [];
        $bin = $d . '/resp/' . $rid . '.bin';
        http_response_code((int)($info['status'] ?? 200));
        header('Content-Type: ' . ($info['tipo'] ?? 'application/octet-stream'));
        header('Cache-Control: no-store');
        if (!empty($info['disp'])) header('Content-Disposition: ' . $info['disp']);
        if (is_file($bin)) { header('Content-Length: ' . filesize($bin)); }
        // Login errado no telefone também conta para o bloqueio.
        if ($p === 'login' && is_file($bin) && filesize($bin) < 200) {
            $r = json_decode((string)file_get_contents($bin), true);
            relay_falha($id, !(is_array($r) && !empty($r['ok'])));
        }
        if (is_file($bin)) readfile($bin);
        @unlink($bin); @unlink($rj);
        exit;
    }
    if (connection_aborted()) break;
    usleep(50000);
} while (microtime(true) < $fim);
@unlink($d . '/req/' . $rid . '.json'); @unlink($d . '/req/' . $rid . '.body');
relay_json(['erro' => 'sem resposta do telefone'], 504);

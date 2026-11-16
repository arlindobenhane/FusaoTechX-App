<?php
/**
 * FusãoTechX Mz • Relay — lado do TELEFONE.
 * Cabeçalhos: X-Dev-Secret (segredo do aparelho), X-Dev-Code (sha256(sal + código de acesso)), X-Dev-Nome, X-Pushing (0/1).
 * Ações (?a=): poll · body · resp · frame
 */
define('FTX_RELAY', 1);
require __DIR__ . '/lib.php';

$id = (string)($_GET['id'] ?? '');
$a = (string)($_GET['a'] ?? '');
if (!relay_id_ok($id)) relay_json(['erro' => 'id'], 400);
$seg = (string)($_SERVER['HTTP_X_DEV_SECRET'] ?? '');
if (!preg_match('/^[a-f0-9]{32,64}$/', $seg)) relay_json(['erro' => 'segredo'], 401);

// Autenticação do aparelho: o primeiro a usar um ID fica dono dele (o ID é aleatório, gerado no telefone).
$m = relay_meta($id);
if ($m === null) {
    $m = ['h' => hash('sha256', $seg), 'criado' => time(), 'sal' => bin2hex(random_bytes(8))];
} elseif (!hash_equals((string)$m['h'], hash('sha256', $seg))) {
    relay_json(['erro' => 'segredo'], 403);
}
$d = relay_dir($id);
$m['visto'] = time();
$nome = trim((string)($_SERVER['HTTP_X_DEV_NOME'] ?? ''));
if ($nome !== '') $m['nome'] = mb_substr(rawurldecode($nome), 0, 60);
$ch = strtolower((string)($_SERVER['HTTP_X_DEV_CODE'] ?? ''));
// O telefone nunca envia o código: envia sha256(sal + código), com o sal que o relay devolve em cada resposta.
// Assim o relay consegue confirmar o código que o PC escreve, sem o guardar.
if (preg_match('/^[a-f0-9]{64}$/', $ch)) $m['ch'] = $ch;
relay_meta_save($id, $m);
$want = function () use ($d): bool { $f = $d . '/want'; return is_file($f) && time() - filemtime($f) < RELAY_WANT_S; };

switch ($a) {
    case 'poll':
        // Espera até haver pedidos do PC (ou o PC começar/parar de ver o ecrã).
        session_write_close();
        relay_limpar($d);
        $pushing = ($_SERVER['HTTP_X_PUSHING'] ?? '0') === '1';
        $fim = microtime(true) + RELAY_POLL_S;
        $lista = [];
        do {
            foreach ((array)glob($d . '/req/*.json') as $f) {
                $novo = substr($f, 0, -5) . '.taken';
                if (@rename($f, $novo)) {
                    $r = json_decode((string)@file_get_contents($novo), true);
                    @unlink($novo);
                    if (is_array($r)) $lista[] = $r;
                }
            }
            if ($lista || $want() !== $pushing) break;
            if (connection_aborted()) exit;
            usleep(120000);
        } while (microtime(true) < $fim);
        relay_json(['reqs' => $lista, 'want' => $want(), 'sal' => $m['sal']]);

    case 'body':
        $rid = (string)($_GET['rid'] ?? '');
        if (!relay_rid_ok($rid) || !is_file($d . '/req/' . $rid . '.body')) relay_json(['erro' => 'rid'], 404);
        header('Content-Type: application/octet-stream');
        header('Content-Length: ' . filesize($d . '/req/' . $rid . '.body'));
        readfile($d . '/req/' . $rid . '.body');
        @unlink($d . '/req/' . $rid . '.body');
        exit;

    case 'resp':
        $rid = (string)($_GET['rid'] ?? '');
        if (!relay_rid_ok($rid)) relay_json(['erro' => 'rid'], 400);
        $tmp = $d . '/resp/' . $rid . '.part';
        $in = fopen('php://input', 'rb'); $out = fopen($tmp, 'wb');
        $n = stream_copy_to_stream($in, $out, RELAY_MAX_BODY);
        fclose($in); fclose($out);
        rename($tmp, $d . '/resp/' . $rid . '.bin');
        $info = [
            'status' => max(100, min(599, (int)($_SERVER['HTTP_X_STATUS'] ?? 200))),
            'tipo' => substr(preg_replace('/[\r\n]/', '', (string)($_SERVER['HTTP_X_TYPE'] ?? 'application/octet-stream')), 0, 120),
            'disp' => substr(preg_replace('/[\r\n]/', '', (string)($_SERVER['HTTP_X_DISP'] ?? '')), 0, 400),
            'n' => $n,
        ];
        file_put_contents($d . '/resp/' . $rid . '.tmpj', json_encode($info));
        rename($d . '/resp/' . $rid . '.tmpj', $d . '/resp/' . $rid . '.json');
        relay_json(['ok' => true]);

    case 'frame':
        // Última imagem do ecrã (o PC vai buscá-la em tunel.php/shot).
        $tmp = $d . '/frame.' . bin2hex(random_bytes(3)) . '.part';
        $in = fopen('php://input', 'rb'); $out = fopen($tmp, 'wb');
        $n = stream_copy_to_stream($in, $out, 4 * 1024 * 1024);
        fclose($in); fclose($out);
        if ($n > 100) {
            rename($tmp, $d . '/frame.jpg');
            $seq = (int)@file_get_contents($d . '/frame.seq') + 1;
            file_put_contents($d . '/frame.seq', (string)$seq);
        } else @unlink($tmp);
        relay_json(['want' => $want(), 'sal' => $m['sal']]);

    default:
        relay_json(['erro' => 'acao'], 400);
}

<?php
/**
 * FusãoTechX Mz • Relay — utilitários partilhados.
 *
 * O telefone liga-se PARA FORA (funciona em dados móveis, sem IP público):
 *  - api.php: o telefone pergunta "há pedidos para mim?" (long-poll), responde-lhes e envia imagens do ecrã.
 *  - tunel.php: o PC abre fusaotech.com/relay/d/<ID>/ e cada pedido do painel fica numa fila até o telefone responder.
 * Tudo em ficheiros dentro de data/ (pasta bloqueada ao público). Sem base de dados.
 */
if (!defined('FTX_RELAY')) { http_response_code(404); exit; }

const RELAY_DATA = __DIR__ . '/data';
const RELAY_POLL_S = 20;          // quanto tempo o telefone espera por pedidos em cada ligação
const RELAY_ONLINE_S = 45;        // telefone considerado ligado se falou com o relay há menos disto
const RELAY_WANT_S = 6;           // o PC está a ver o ecrã se pediu uma imagem há menos disto
const RELAY_REQ_TIMEOUT_S = 28;   // tempo máximo de espera do PC por uma resposta
const RELAY_MAX_BODY = 64 * 1024 * 1024;

@set_time_limit(60);
ignore_user_abort(false);

function relay_id_ok($id): bool { return is_string($id) && preg_match('/^[0-9]{9}$/', $id) === 1; }
function relay_rid_ok($r): bool { return is_string($r) && preg_match('/^[a-f0-9]{16}$/', $r) === 1; }

function relay_dir(string $id): string
{
    $d = RELAY_DATA . '/' . $id;
    foreach (['', '/req', '/resp'] as $s) if (!is_dir($d . $s)) @mkdir($d . $s, 0700, true);
    return $d;
}

function relay_meta(string $id): ?array
{
    $f = RELAY_DATA . '/' . $id . '/meta.json';
    if (!is_file($f)) return null;
    $m = json_decode((string)@file_get_contents($f), true);
    return is_array($m) ? $m : null;
}

function relay_meta_save(string $id, array $m): void
{
    $d = relay_dir($id);
    $tmp = $d . '/meta.' . bin2hex(random_bytes(4)) . '.tmp';
    file_put_contents($tmp, json_encode($m, JSON_UNESCAPED_UNICODE));
    @rename($tmp, $d . '/meta.json');
}

function relay_online(?array $m): bool { return $m && (time() - (int)($m['visto'] ?? 0)) < RELAY_ONLINE_S; }

function relay_json($o, int $code = 200): void
{
    http_response_code($code);
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    echo json_encode($o, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

/** Limpa pedidos/respostas abandonados (PC fechou a página, telefone perdeu rede…). */
function relay_limpar(string $d): void
{
    foreach (['req', 'resp'] as $s) {
        foreach ((array)glob($d . '/' . $s . '/*') as $f) {
            if (is_file($f) && filemtime($f) < time() - 90) @unlink($f);
        }
    }
}

/** IP do visitante (para limitar tentativas de código erradas). */
function relay_ip(): string { return substr((string)($_SERVER['REMOTE_ADDR'] ?? '0'), 0, 45); }

/** Bloqueio simples contra adivinhar o código: 8 falhas por IP+telefone → 10 minutos bloqueado. */
function relay_bloqueado(string $id): bool
{
    $f = RELAY_DATA . '/' . $id . '/falhas.json';
    $l = is_file($f) ? (json_decode((string)@file_get_contents($f), true) ?: []) : [];
    $k = md5(relay_ip());
    return isset($l[$k]) && $l[$k]['n'] >= 8 && time() - $l[$k]['t'] < 600;
}

function relay_falha(string $id, bool $falhou): void
{
    $f = relay_dir($id) . '/falhas.json';
    $l = is_file($f) ? (json_decode((string)@file_get_contents($f), true) ?: []) : [];
    $k = md5(relay_ip());
    if (!$falhou) { if (isset($l[$k])) { unset($l[$k]); @file_put_contents($f, json_encode($l)); } return; }
    if (!isset($l[$k]) || time() - $l[$k]['t'] >= 600) $l[$k] = ['n' => 0, 't' => time()];
    $l[$k]['n']++;
    foreach ($l as $kk => $v) if (time() - $v['t'] > 3600) unset($l[$kk]);
    @file_put_contents($f, json_encode($l));
}

/** O código que o PC indicou corresponde ao do telefone? (o telefone envia só o hash, nunca o código) */
function relay_codigo_ok(array $m, string $k): bool
{
    return $k !== '' && !empty($m['ch']) && hash_equals((string)$m['ch'], hash('sha256', $m['sal'] . $k));
}

/** Uma vez por dia: apaga telefones sem atividade há mais de 30 dias (filas, imagens e dados). */
function relay_purga(): void
{
    $marca = RELAY_DATA . '/.purga';
    if (is_file($marca) && filemtime($marca) > time() - 86400) return;
    @touch($marca);
    foreach ((array)glob(RELAY_DATA . '/[0-9]*', GLOB_ONLYDIR) as $d) {
        $m = @json_decode((string)@file_get_contents($d . '/meta.json'), true);
        if (is_array($m) && time() - (int)($m['visto'] ?? 0) < 30 * 86400) continue;
        if (!is_array($m) && filemtime($d) > time() - 86400) continue;
        $it = new RecursiveIteratorIterator(new RecursiveDirectoryIterator($d, FilesystemIterator::SKIP_DOTS), RecursiveIteratorIterator::CHILD_FIRST);
        foreach ($it as $f) $f->isDir() ? @rmdir($f->getPathname()) : @unlink($f->getPathname());
        @rmdir($d);
    }
}

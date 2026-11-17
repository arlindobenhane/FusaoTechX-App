<?php
/**
 * FusãoTechX Mz • Relay — utilitários partilhados.
 *
 * O telefone liga-se PARA FORA (funciona em dados móveis, sem IP público):
 *  - api.php: o telefone pergunta "há pedidos para mim?" (long-poll), responde-lhes e envia imagens do ecrã.
 *  - tunel.php: o PC abre studio.fusaotech.com/d/<ID>/ e cada pedido do painel fica numa fila até o telefone responder.
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

// ============================================================
// Licenças e administração (conta de admin que gere tudo)
// ============================================================

function cfg_file(): string { return RELAY_DATA . '/admin.json'; }

function cfg_get(): array
{
    $f = cfg_file();
    $c = is_file($f) ? json_decode((string)@file_get_contents($f), true) : null;
    if (!is_array($c)) $c = [];
    // Valores por omissão.
    $c += ['user' => null, 'hash' => null, 'criado' => 0, 'politica_novos' => 'trial', 'trial_dias' => 7, 'sal' => bin2hex(random_bytes(8))];
    return $c;
}

function cfg_set(array $c): void
{
    if (!is_dir(RELAY_DATA)) @mkdir(RELAY_DATA, 0700, true);
    $tmp = cfg_file() . '.' . bin2hex(random_bytes(4)) . '.tmp';
    file_put_contents($tmp, json_encode($c, JSON_UNESCAPED_UNICODE));
    @rename($tmp, cfg_file());
}

function admin_existe(): bool { $c = cfg_get(); return !empty($c['user']) && !empty($c['hash']); }

/** Licença de um aparelho (ficheiro próprio, só o admin escreve). */
function lic_get(string $id): array
{
    $f = RELAY_DATA . '/' . $id . '/lic.json';
    $l = is_file($f) ? json_decode((string)@file_get_contents($f), true) : null;
    if (!is_array($l)) $l = [];
    // nome (rótulo), estado (ativo|bloqueado), ate (timestamp 0=ilimitado), nota, criado
    return $l + ['nome' => '', 'estado' => '', 'ate' => null, 'nota' => '', 'criado' => 0, 'por' => ''];
}

function lic_set(string $id, array $l): void
{
    if (!relay_id_ok($id)) return;
    relay_dir($id);
    $tmp = RELAY_DATA . '/' . $id . '/lic.' . bin2hex(random_bytes(4)) . '.tmp';
    file_put_contents($tmp, json_encode($l, JSON_UNESCAPED_UNICODE));
    @rename($tmp, RELAY_DATA . '/' . $id . '/lic.json');
}

/**
 * Pode este aparelho usar o serviço agora?
 * Devolve ['ok'=>bool, 'motivo'=>'', 'ate'=>ts|0, 'nome'=>'', 'estado'=>''].
 * Regras: bloqueado => não; com validade expirada => não; sem licença => política de novos (trial/bloquear).
 */
function lic_estado(string $id): array
{
    $c = cfg_get();
    $l = lic_get($id);
    $base = ['nome' => $l['nome'], 'ate' => $l['ate'], 'estado' => $l['estado']];
    if ($l['estado'] === 'bloqueado') return $base + ['ok' => false, 'motivo' => 'bloqueado'];
    if (!empty($l['criado'])) {
        // Já tem licença definida pelo admin.
        if (!empty($l['ate']) && time() > (int)$l['ate']) return $base + ['ok' => false, 'motivo' => 'expirada'];
        return $base + ['ok' => true, 'motivo' => ''];
    }
    // Aparelho novo, sem licença: aplica a política.
    if (($c['politica_novos'] ?? 'trial') === 'bloquear') return $base + ['ok' => false, 'motivo' => 'por_autorizar'];
    // Trial automático: cria uma licença de avaliação na primeira vez.
    $ate = time() + max(1, (int)($c['trial_dias'] ?? 7)) * 86400;
    lic_set($id, lic_get($id) + ['estado' => 'ativo', 'ate' => $ate, 'criado' => time(), 'por' => 'trial', 'nome' => 'Avaliação']);
    return ['ok' => true, 'motivo' => 'trial', 'ate' => $ate, 'nome' => 'Avaliação', 'estado' => 'ativo'];
}

/** Lista todos os aparelhos conhecidos (para o painel do admin). */
function lic_todos(): array
{
    $out = [];
    foreach ((array)glob(RELAY_DATA . '/[0-9]*', GLOB_ONLYDIR) as $d) {
        $id = basename($d);
        if (!relay_id_ok($id)) continue;
        $m = relay_meta($id);
        $l = lic_get($id);
        $out[] = [
            'id' => $id, 'nome' => $l['nome'], 'estado' => $l['estado'] ?: 'novo', 'ate' => $l['ate'],
            'criado' => $l['criado'], 'nota' => $l['nota'],
            'online' => relay_online($m), 'visto' => $m['visto'] ?? 0,
            'aparelho' => $m['nome'] ?? '', 'relayUrl' => '',
        ];
    }
    usort($out, fn($a, $b) => ($b['visto'] <=> $a['visto']));
    return $out;
}

// --- Sessão do administrador ---
function admin_sessao_iniciar(): void
{
    if (session_status() !== PHP_SESSION_ACTIVE) {
        session_name('ftxadm');
        @session_start(['cookie_httponly' => true, 'cookie_samesite' => 'Lax']);
    }
}
function admin_logado(): bool { admin_sessao_iniciar(); return !empty($_SESSION['ftx_admin']); }
function admin_h($s): string { return htmlspecialchars((string)$s, ENT_QUOTES, 'UTF-8'); }

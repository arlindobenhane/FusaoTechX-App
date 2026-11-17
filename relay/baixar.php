<?php
/**
 * FusãoTechX Mz • Descarga direta da app (studio.fusaotech.com/app).
 * Serve o ficheiro .apk com descarga automática — sem passar pelo GitHub nem por páginas.
 * Usa a cópia local se existir (rápido); senão vai buscar a última versão publicada.
 */
$local = __DIR__ . '/FusaoTechX.apk';
$nome = 'FusaoTechX.apk';
header('Content-Type: application/vnd.android.package-archive');
header('Content-Disposition: attachment; filename="' . $nome . '"');
header('Cache-Control: no-cache');
header('X-Content-Type-Options: nosniff');
if (is_file($local)) {
    header('Content-Length: ' . filesize($local));
    readfile($local);
    exit;
}
// Sem cópia local: vai buscar a última versão ao repositório e entrega-a já descarregada.
$url = 'https://github.com/arlindobenhane/FusaoTechX-App/releases/download/apk/FusaoTechX.apk';
if (function_exists('curl_init')) {
    $ch = curl_init($url);
    curl_setopt_array($ch, [CURLOPT_FOLLOWLOCATION => true, CURLOPT_TIMEOUT => 120, CURLOPT_FAILONERROR => true,
        CURLOPT_USERAGENT => 'FusaoTechX', CURLOPT_WRITEFUNCTION => function ($c, $d) { echo $d; return strlen($d); }]);
    curl_exec($ch);
    curl_close($ch);
} else {
    readfile($url);
}

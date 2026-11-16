# Relay — ligar o telefone e o PC em redes diferentes

O telefone nos **dados móveis** e o PC noutra rede qualquer: a operadora bloqueia
ligações a entrar no telefone, por isso o telefone liga-se **para fora** a um ponto
de encontro público — este relay, que corre em `fusaotech.com/relay` (PHP, sem base de dados).

## Como usar
1. No telefone: app FusãoTechX → **3 · Ligação remota** → ativar → **LIGAR**.
2. No PC: abrir **fusaotech.com/relay**, escrever o **ID** (9 dígitos) e depois o **código**.

## Como funciona
- `api.php` — o telefone pergunta por pedidos (long-poll de 20 s), responde e envia imagens do ecrã.
- `tunel.php` — `fusaotech.com/relay/d/<ID>/…`: cada pedido do painel fica numa fila até o telefone responder.
  O telefone executa-o no seu próprio servidor (o mesmo da rede local), por isso o código continua a ser verificado no telefone.
- O ecrã ao vivo: o telefone só envia imagens enquanto o PC está a ver (até ~10 por segundo).
- `data/` guarda filas e a última imagem (pasta bloqueada ao público; os ficheiros antigos apagam-se sozinhos).

## Segurança
- O ID é aleatório e fica ligado a um segredo do telefone: outro aparelho não pode usar o mesmo ID.
- O relay nunca guarda o código: o telefone envia só `sha256(sal + código)`.
- 8 códigos errados seguidos do mesmo IP → 10 minutos bloqueado (e o telefone tem o seu próprio bloqueio).
- Se precisar, «Gerar um ID novo» na app invalida o ID antigo.

## Instalar noutro servidor
Copiar a pasta `relay/` para o servidor (PHP 7.4+, Apache com `.htaccess`) e, na app,
mudar o endereço do relay (por omissão `https://fusaotech.com/relay`).

FusãoTechX Mz · +258 83 333 7906 · fusaotech.com

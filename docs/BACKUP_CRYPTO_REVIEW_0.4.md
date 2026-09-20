# Revisão final do motor 0.4.2 — 2026-09-19

Revisão de código e testes nesta implementação; **não é auditoria independente nem
certificação de prontidão para produção**. O motor permanece isolado. Nenhum fluxo de
backup, banco, Reader, biblioteca, sessão, notificação ou tela foi modificado.

## Resultado da revisão

- AES-256-GCM via JCA, chave 32 bytes, nonce 12 bytes e tag 16 bytes; objetos Cipher
  novos por chamada. Sem estado criptográfico reutilizado entre operações.
- Argon2id 1.3 pela API lightweight Bouncy Castle 1.86, perfil 64 MiB/t=3/p=4 preservado.
  Salt e nonce são novos por SecureRandom; unicidade probabilística, não garantida por
  registro global. Sem RNG determinístico no motor.
- Os 64 bytes exatos do cabeçalho são AAD. Conferidos offsets, endian, magic, IDs,
  parâmetros, salt, nonce, comprimento, ciphertext e tag com a especificação e fixtures.
- Todos os comprimentos/parâmetros são validados antes da coleta do corpo/KDF. Cabeçalho
  fixo, multiplicação de orçamento em Long, trabalho KDF limitado e sem fallback.
- Plaintext fica em buffer privado até autenticação. Erros do provedor são substituídos
  por códigos sem causa; o pacote não escreve logs, temporários ou diagnósticos sensíveis.
- Semáforo por processo limita concorrência; testes verificam BUSY e liberação após falha.
- `.tachibk` não é reconhecido por este motor. Versões/IDs desconhecidos são rejeitados;
  os leitores convencionais não receberam alterações.
- Não foi identificado bloqueio restante para integrar **o componente isolado**.
  As limitações abaixo impedem tratar esta conclusão como aprovação de um fluxo real.

### Correção realizada

`CancellationException` de streams era absorvida pelo catch de RuntimeException e a
interrupção da thread não era observada. Agora cancelamento é preservado com mensagem
fixa `CANCELLED`, sem causa sensível, e há checagens cooperativas em fronteiras de leitura,
KDF, cifra e devolução. Um resultado já autenticado é limpo se a interrupção for detectada
antes da entrega. Três regressões cobrem interrupção prévia, cancelamento de streams e
limpeza do buffer após autenticação. Formato, algoritmos e parâmetros não mudaram.

Ainda não há interrupção preemptiva de Argon2/JCA/stream bloqueado, detecção automática de
Job de coroutine cancelado ou garantia contra corrida após a última checagem. A futura
integração deve ligar o ciclo de vida à thread e descartar resultados após cancelamento.
Falhas de publicação podem deixar ciphertext parcial; não existe promessa de atomicidade.

## Independência e dependências

O teste Argon2 usa a saída publicada na RFC 9106 §5.3, com secret e additional data;
o teste AES usa o caso público 14 de McGrew/Viega, incluindo comparação do ciphertext/tag
exatos. Não são apenas testes de ida e volta. Os fixtures v1 foram gerados por libargon2
de referência e cryptography/OpenSSL, com perfil de produção e hashes fixados; os mesmos
bytes continuaram legíveis na JVM e no Android. Alteração da tag foi rejeitada usando a
senha correta dos fixtures, inclusive para payload vazio.

O build verifica classes duplicadas e dex/empacotamento. Não foi encontrado conflito de
classes no caminho exercitado. A [orientação Android sobre provedores](https://developer.android.com/privacy-and-security/cryptography)
é respeitada: AES não fixa `BC`, e a API lightweight não registra provedor global.
No API 36 testado, o provedor é AndroidOpenSSL; o próprio App só insere Conscrypt 2.7.0
em versões anteriores ao Android 10. Esse caminho antigo ainda precisa de teste real.

A [licença da tag 1.86](https://raw.githubusercontent.com/bcgit/bc-java/r1rv86/LICENSE.html)
é MIT, permissiva, com obrigação de preservar copyright e aviso. A aplicação usa
Apache-2.0; não foi identificado conflito entre essas condições. AboutLibraries já
inclui o nome/link da dependência. Foi acrescentado `assets/licenses/bouncycastle.txt`,
com o aviso integral distribuído no JAR, para preservá-lo também após shrinking.
O aviso embutido no JAR mantém copyright 2000–2023; a página da tag traz 2000–2026.

As [notas oficiais 1.86](https://www.bouncycastle.org/resources/new-release-bouncy-castle-java-1-86/)
registram correções, inclusive de custos KDF não limitados em entradas PBE. O motor impõe
seus próprios limites antes de chamar Argon2. Não se conclui ausência de vulnerabilidades
na dependência apenas pela versão ou por estes testes; monitoramento continua necessário.

## Método de desempenho e memória

Sonda em `backup-crypto-benchmark/Benchmark.java`, compilada contra os **mesmos .class**
do motor debug, Kotlin stdlib, Okio e BC. Não foi criada outra implementação criptográfica.
`prepare.py` usa somente dependências locais. Fontes e logs sintéticos estão preservados
nesse diretório. Nenhuma biblioteca pessoal, credencial ou configuração do aparelho foi usada.

Cada tamanho executa em processo novo; uma amostra por estágio, sem pretensão estatística.
KDF é medida separadamente; AES separado exclui KDF; engineEncrypt/Decrypt incluem KDF,
validação e cópias. As chamadas posteriores podem se beneficiar de JIT/caches. GC explícito
e 100 ms de espera ocorrem **somente na sonda** antes de cada estágio medido.
Heap ocupado é amostrado a cada ~1 ms: o pico observado pode perder picos curtos e inclui
lixo ainda não coletado. Incremento = pico menos baseline desse estágio. `/proc/self/status`
fornece VmHWM do próprio processo: pico de RSS vitalício, incluindo bibliotecas, JIT,
memória nativa e sampler; não confundir com heap ou incremento de uma operação.
O payload original permanece vivo para comparar a restauração; isso aumenta o baseline
do benchmark em relação a uma restauração que só tenha o arquivo criptografado.

### JVM local — JDK 21, SunJCE, heap máximo 1.024 MiB

Tempos em ms. Picos e incrementos em MiB, somente chamadas completas do motor.

| Payload MiB | KDF | AES enc/dec | Motor enc/dec | Pico heap enc/dec | Incremento enc/dec |
| --- | --- | --- | --- | --- | --- |
| 0 | 354 | 10 / 1 | 203 / 184 | 75,9 / 75,1 | 69,3 / 68,5 |
| 1 | 334 | 34 / 15 | 221 / 209 | 82,8 / 85,8 | 74,1 / 75,2 |
| 8 | 364 | 114 / 97 | 294 / 276 | 111,1 / 112,3 | 95,5 / 87,7 |
| 32 | 368 | 382 / 367 | 597 / 569 | 207,0 / 208,5 | 167,3 / 135,9 |
| 64 | 330 | 777 / 747 | 948 / 1004 | 267,2 / 376,6 | 195,5 / 240,0 |
| 128 | 347 | 1557 / 1555 | 1788 / 1771 | 523,2 / 719,2 | 387,5 / 454,5 |

Todos os round trips dessa tabela passaram. Argon2 adicionou ~71–80 MiB de heap observado.
VmHWM no processo de 128 MiB: 956.544 KiB (~934 MiB). Heap 128 MiB/payload vazio e
heap 256 MiB/payload 32 MiB foram recusados na admissão com INSUFFICIENT_RESOURCES.

### Android real — Samsung SM-A165M, ARM64, API 36, AndroidOpenSSL

Sonda DEX via `dalvikvm64` em diretório temporário exclusivo do shell, sem instalar/abrir
o Pindorama. DEX produzido pelo D8 9.4.14 do AGP 9.4.0 usado pelo projeto, min-api 26.
O D8 antigo do SDK 36 emitia avisos de metadados Kotlin 2.4; foi usado o D8 do projeto
para evitar essa incompatibilidade da ferramenta de sonda, sem trocar dependências.
`-Xmx` limitou apenas cada processo temporário: nenhuma configuração de segurança/heap
do aparelho ou aplicativo foi modificada. Não é medição dentro do processo completo do app.

| Heap MiB | Payload MiB | KDF ms | AES enc/dec ms | Motor enc/dec ms | Pico heap enc/dec MiB | Incremento enc/dec MiB |
| --- | --- | --- | --- | --- | --- | --- |
| 256 | 0 | 611 | 2 / 1 | 536 / 575 | 68,1 / 68,1 | 66,6 / 66,7 |
| 256 | 1 | 605 | 3 / 3 | 530 / 581 | 72,1 / 72,2 | 69,6 / 68,7 |
| 256 | 8 | 599 | 11 / 11 | 549 / 658 | 100,1 / 102,3 | 90,6 / 84,8 |
| 256 | 16 | 611 | 20 / 20 | 566 / 614 | 132,1 / 136,3 | 114,6 / 102,8 |
| 512 | 32 | 613 | 39 / 39 | 606 / 666 | 196,1 / 203,0 | 162,6 / 137,5 |
| 512 | 64 | 602 | 80 / 76 | 723 / recusado | 324,1 / — | 258,6 / — |

Round trips do motor até 16 MiB/heap 256 e 32 MiB/heap 512 passaram. Argon2 adicionou
~66,6–66,8 MiB. VmHWM de 32 MiB/heap 512: 348.840 KiB (~341 MiB).
Casos 32 MiB/heap 256, 128 MiB/heap 512 e vazio/heap 128 foram recusados antes da KDF.
64 MiB/heap 512 criptografou, mas a admissão da restauração recusou: o benchmark retinha
payload e arquivo (~129 MiB), além dos 400 MiB adicionais exigidos pelo orçamento.
Esse caso não significa que toda restauração de 64 MiB falha com heap 512.

Na sequência extra de fixtures após o caso vazio/heap 256 houve recusa conservadora por
heap ocupado por objetos aguardando GC. As etapas já medidas haviam passado; o log
mantém a recusa. Os dois fixtures foram então testados em processos de heap 512 e ambos
passaram, inclusive tag adulterada com a senha correta. Não se reduziu o perfil KDF nem
o orçamento para fazer os testes passarem. Essa sensibilidade a GC deve ser tratada na
UX e no planejamento de memória futuros; o motor não força GC ou faz retry automático.

## Cópias e interação dos limites

Criação mantém entrada do chamador, snapshot privado, ciphertext e concatenação final
do envelope, além de buffers do provedor. Leitura por stream coleta chunks Okio, copia
para array de ciphertext e aloca saída privada. O overload ByteArray também segue esse
caminho, podendo manter simultaneamente o arquivo original e uma cópia de ciphertext.
Há oportunidades de eliminar cópia do ciphertext/concatenação, mas não são necessárias
para a validade do formato. Foram mantidas nesta revisão para não combinar otimizações
com a correção de cancelamento; documentadas para medição/revisão antes da integração real.

128 MiB é limite do **payload opaco de entrada**, futuramente GZIP; 64 MiB é limite do
**Protobuf após descompressão** no leitor convencional. Um arquivo protegido autenticado
não ganha permissão para expandir acima de 64 MiB. O motor não comprime nem descomprime,
e não garante que todo payload opaco será um backup válido. A futura restauração deve
autenticar primeiro, aplicar os limites convencionais depois e liberar buffers entre
fases para não somar desnecessariamente KDF, ciphertext, plaintext e grafo Protobuf.
Nenhum limite foi aumentado. O teto de formato não é uma garantia de suporte em todo
aparelho: preservar INSUFFICIENT_RESOURCES, sem downgrade automático de segurança.

## Antes de integrar aos backups reais

- Testar API 26/28, Conscrypt efetivo nesse caminho, outras capacidades/ABIs e execução
  dentro do processo completo, com consumo de heap do aplicativo e pressão de memória.
- Reduzir cópias mediante testes, medir sobreposição com GZIP/Protobuf, tratar recusas
  conservadoras e projetar cancelamento de coroutine, I/O bloqueado e morte do processo.
- Projetar senha/recuperação, publicação/retenção SAF, backup automático e restauração
  autenticada seguida de validação semântica/transação, sem anunciar atomicidade inexistente.
- Revisão independente adicional, acompanhamento da dependência e testes de dispositivos;
  senhas fracas, aparelho comprometido, cópias em memória e backups antigos seguem riscos.

Os resultados da sonda não aumentam o número de testes JUnit. A suíte tinha 188 testes;
foram acrescentadas três regressões de cancelamento: **191 testes passaram**, sem falhas,
erros ou testes ignorados. `spotlessCheck`, `testDebugUnitTest` e `:app:assembleDebug`
passaram com o JDK 21 local na branch de implementação antes do commit. A validação
da principal após o merge é registrada no relatório da integração.

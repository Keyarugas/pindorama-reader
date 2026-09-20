# Motor criptográfico isolado — Pindorama 0.4.2

## Estado e escopo

O pacote interno `eu.kanade.tachiyomi.data.backup.crypto` implementa somente um
envelope de bytes. Não está registrado na injeção de dependências, navegação,
criação/restauração real ou backup automático. Não acessa banco, rede, provedores
Android, notificações ou arquivos temporários. Não interpreta GZIP/Protobuf.
Os modelos e o formato `.tachibk`, inclusive a política 0.4.1, permanecem inalterados.
Os únicos arquivos `.pindobk` incluídos nesta etapa são fixtures sintéticos de testes.

## Modelo de ameaças

Objetivo: confidencialidade e detecção de alteração de um arquivo obtido por terceiros,
inclusive armazenamento remoto não confiável e tentativas de senha offline. O parser
trata bytes, comprimentos e parâmetros como hostis; nunca usa metadados para restaurar
dados antes da autenticação. A autenticação global/biometria do aplicativo não participa.

Não protege dispositivo comprometido, teclado malicioso, depurador, captura de memória,
dados já restaurados, backups convencionais antigos ou senha fraca. Não impede exclusão,
substituição por outro backup válido, rollback ou indisponibilidade do armazenamento.
Autenticidade significa posse da chave derivada, não identidade de uma pessoa.
Quem conhece a senha consegue produzir outro arquivo válido.

## Componentes e dependências

| Componente | Responsabilidade |
| --- | --- |
| `BackupCrypto` | API síncrona, limites, ordem das operações e limpeza de buffers |
| `PindobkFormat` / `Argon2Profile` | Codificação canônica, reconhecimento e validação estrutural |
| `Argon2KeyDerivation` | UTF-8 estrito e derivação de chave |
| `BackupAesGcm` | JCA AEAD; saída privada até `doFinal` ter sucesso |
| `CryptoMemoryBudget` | Admissão conservadora conforme heap disponível |
| `BackupCryptoError` | Códigos fixos, sem mensagens/cause de provedores |

Dependência nova: `org.bouncycastle:bcprov-jdk15to18:1.86`, API lightweight de
Argon2id, Java sem JNI. Não registra nem substitui um provedor JCA global. A variante
Java 1.5–1.8 evita depender de APIs recentes/multi-release JAR para o Android mínimo
26 do projeto. AES usa `Cipher.getInstance("AES/GCM/NoPadding")`, sem fixar provedor,
com `GCMParameterSpec`; as APIs utilizadas existem no API 26. Conscrypt 2.7.0 já era
dependência do projeto e não foi alterado. Okio, já presente, coleta ciphertext limitado.

Bouncy Castle é mantido; a versão foi confirmada no Maven Central e nas notas de
[lançamento 1.86](https://www.bouncycastle.org/resources/new-release-bouncy-castle-java-1-86/).
Usar uma biblioteca mantida não substitui revisão de dependências nem acompanhamento
de vulnerabilidades. A implementação de Argon2 percorre lanes em Java; `p=4` é um
parâmetro criptográfico, não promessa de quatro threads/cores em paralelo.

## Algoritmos, senha e recursos

- AES-256-GCM: chave de 32 bytes, nonce de 12 bytes, tag de 16 bytes, sem padding.
- Argon2id versão 1.3 (`0x13`), saída de 32 bytes, sem secret/pepper nem additional data
  da KDF. O cabeçalho é AAD do GCM, não additional data do Argon2.
- Perfil de criação: **65.536 KiB (64 MiB), 3 passagens, 4 lanes**, segundo perfil
  recomendado pela [RFC 9106 §7.4](https://www.rfc-editor.org/rfc/rfc9106.html#section-7.4).
- Salt de 16 bytes e nonce de 12 bytes novos em cada criação por `SecureRandom`.
  Unicidade é probabilística; não há contador persistente ou promessa matemática de
  ausência de colisões. Salt novo também separa as chaves de arquivos com a mesma senha.
- Senha explícita em `CharArray`, UTF-8 estrito, sem BOM, terminador, normalização,
  trim ou conversão intermediária para `String`. NUL é um caractere significativo.
  Surrogates isolados são rejeitados. Limites: 1–1.024 unidades UTF-16 e no máximo
  4.096 bytes UTF-8. Senha vazia é rejeitada; payload vazio é permitido.
- O motor não mede a força da senha nem a armazena. A futura UI precisará explicar
  que a resistência a tentativas offline depende da força da senha; limites de tamanho
  não constituem política de força. Sem senha correta não existe recuperação/backdoor.

Leitura aceita somente `65536 <= m <= 131072`, `3 <= t <= 6`, `1 <= p <= 4`,
`m*t <= 393216` KiB-passagens e `m % (4*p) == 0`. A restrição de divisibilidade evita
arredondamentos implícitos de memória. Estes intervalos são limites de aceitação,
não seleção automática de perfil. Campos negativos/valores unsigned fora dos limites
são rejeitados antes da KDF; não existe fallback ou redução automática de parâmetros.

### Compatibilidade e medições

Compilação Kotlin e testes JVM utilizam o JDK 21 local. Vetores independentes confirmam
as primitivas e fixtures confirmam o envelope; o build Android valida dex/empacotamento.
Na implementação inicial não havia dispositivo conectado. A revisão posterior executou
uma sonda isolada em ARM64/API 36 com AndroidOpenSSL, incluindo fixtures independentes,
payload vazio e tag inválida. API 26 e Conscrypt do aplicativo em Android anterior ao
10 continuam pendentes. Resultados e método: [revisão final](BACKUP_CRYPTO_REVIEW_0.4.md).

Uma sonda JVM separada, apenas sintética, com o mesmo Argon2id/Bouncy Castle 1.86 e
`-Xmx256m`, mediu 223,6 ms na primeira derivação e 171,8 / 143,5 / 147,3 ms nas três
seguintes. Heap ocupado observado após cada chamada: 75 / 113 / 152 / 164 MiB,
incluindo objetos aguardando GC; **não é medição de pico nem benchmark Android**.
Com `-Xmx128m`, a mesma fórmula de admissão recusou a operação antes da KDF.
Esses números não justificam reduzir o perfil em dispositivos lentos.

## Formato `.pindobk`, versão 1

Arquivo = cabeçalho de **64 bytes** + ciphertext de **N bytes** + tag de **16 bytes**.
Todos os inteiros são unsigned big-endian; a implementação rejeita valores fora dos
limites antes de converter para tamanhos de buffers. Não há campos opcionais, strings,
padding, checksum separado ou extensões anexadas. Tamanho total exato: `N + 80`.

| Offset | Bytes | Campo | Valor/regra v1 |
| --- | --- | --- | --- |
| 0 | 8 | Magic | `50 49 4e 44 4f 42 4b 00` (`PINDOBK\0`) |
| 8 | 2 | Versão | `1` |
| 10 | 2 | Tamanho do cabeçalho | `64`, obrigatório e máximo nesta versão |
| 12 | 1 | Cifra | `1` = AES-256-GCM, nonce 12/tag 16 |
| 13 | 1 | KDF | `1` = Argon2id, saída 32 |
| 14 | 1 | Versão Argon2 | `0x13` |
| 15 | 1 | Tipo do payload | `0` = bytes opacos |
| 16 | 4 | Memória Argon2 | KiB, dentro da política acima |
| 20 | 4 | Passagens Argon2 | Dentro da política acima |
| 24 | 4 | Paralelismo Argon2 | Lanes, dentro da política acima |
| 28 | 8 | Comprimento do payload | `N`, entre 0 e 134.217.728 inclusive |
| 36 | 16 | Salt | Aleatório por arquivo |
| 52 | 12 | Nonce | Aleatório por operação |
| 64 | N | Ciphertext | Mesmo comprimento do payload |
| 64 + N | 16 | Tag GCM | Obrigatória, sem bytes adicionais após ela |

**AAD são exatamente os 64 bytes recebidos**, não um cabeçalho reserializado. Qualquer
alteração é rejeitada pela validação estrutural ou pela autenticação. Alteração de
parâmetros ainda válidos também falha na autenticação. Versão/cifra/KDF desconhecida
não é interpretada como backup convencional. O motor não possui esse fallback.

`identify` requer ao menos os 10 primeiros bytes, reconhece magic e informa versão,
inclusive desconhecida. É informação pública **não autenticada**, não validação de
backup nem autorização para restaurar. A extensão não participa do reconhecimento.

Metadados observáveis: formato, versão, algoritmos, parâmetros, salt, nonce, tipo opaco
e **comprimento exato do payload** (não há padding). Armazenamento pode revelar nome,
horários e padrões de acesso fora do envelope. O motor não escolhe nomes. Não há
títulos, contagens de obras, categorias, fontes, URLs, credenciais, timestamps internos,
identidade do aparelho/usuário ou sinalizador de obras privadas no cabeçalho.

## Validação, API e recuperação

API interna: `encrypt(ByteArray, CharArray): ByteArray`,
`encryptTo(ByteArray, CharArray, OutputStream)`,
`decrypt(ByteArray|InputStream, CharArray): ByteArray` e `identify(ByteArray)`.
Executar fora da thread principal. Streams pertencem ao chamador; o motor não fecha,
publica, sincroniza ou apaga destinos. O chamador não deve modificar arrays durante
a chamada e deve limpar senha/payload próprios quando não forem mais necessários.

Ordem de leitura: ler exatamente 64 bytes; validar magic/versão/algoritmos/campos/KDF/
comprimento; verificar orçamento de heap e codificação da senha; coletar ciphertext
em blocos de até 8 KiB e exigir tag completa e EOF; derivar chave; autenticar com GCM;
somente então devolver o payload. Comprimentos declarados não causam uma alocação
imediata daquele tamanho. Não se faz descompressão ou parsing semântico nesta etapa.
Não existe callback/output stream de plaintext. Mesmo se o provedor escrever bytes
antes de validar a tag, a saída é privada, apagada em falha e nunca devolvida.

Para recuperar: abrir bytes do arquivo, reconhecer versão, fornecer a senha original
com os mesmos caracteres, chamar decrypt e consumir apenas o resultado de sucesso.
Senha incorreta e tag inválida produzem o mesmo erro genérico; não se tenta recuperar
fragmentos. O motor nunca modifica banco ou dados reais, independentemente do resultado.
Uma integração futura precisará de validação semântica e estratégia de restauração
próprias; autenticação não transforma restauração incremental em transação.

## Limites e falhas

Payload máximo **128 MiB**; arquivo máximo **128 MiB + 80 bytes**. O limite acomoda
o teto de entrada convencional da 0.4.1; o limite de 64 MiB de Protobuf descomprimido
continua independente e inalterado. O motor não aumenta os limites do leitor legado.
Bibliotecas acima dos limites ou sem heap suficiente são rejeitadas explicitamente.

Admissão usa heap disponível de `Runtime` e exige, além do já ocupado:
`4 * N + 2 * memoriaArgonEmBytes + 16 MiB`. Para o perfil de produção isso corresponde
a 144 MiB com payload vazio, 176 MiB com 8 MiB e 656 MiB com 128 MiB.
São margens conservadoras para cópias, provedor e overhead de objetos Java, não uma
reserva de heap/garantia de pico. O limite de formato não significa que todo aparelho
conseguirá abri-lo. Um semáforo global permite uma operação por processo; outra recebe
`BUSY`, sem enfileirar senhas. A escrita externa após criptografia não mantém esse lock.

São mantidos buffers em memória; não há plaintext temporário em disco. Limpeza de
senha codificada, chave derivada, cópia do payload e saída rejeitada ocorre em `finally`/
tratamento de falha. Bouncy Castle limpa parâmetros e blocos no caminho normal.
JVM/ART, GC, encoder UTF-8 e provedores podem criar cópias fora do controle da aplicação;
não há garantia de zeroização absoluta, especialmente em falha de alocação na biblioteca,
interrupção do processo ou crash. Arrays de entrada e resultado continuam do chamador.

Códigos expostos: `INVALID_FORMAT`, `UNSUPPORTED_VERSION`, `UNSUPPORTED_ALGORITHM`,
`UNSUPPORTED_PARAMETERS`, `SIZE_LIMIT`, `INVALID_PASSWORD_ENCODING`,
`AUTHENTICATION_FAILED`, `INSUFFICIENT_RESOURCES`, `BUSY`, `IO_ERROR`,
`CRYPTO_UNAVAILABLE`. A exceção contém somente o código; sem cause, caminhos, conteúdo
ou mensagem interna. O pacote não usa logging. Diagnósticos futuros devem preservar
essa regra, inclusive ao lidar com streams externos.

OOM detectável é convertido em erro controlado; não há garantia de sobrevivência a
pressão extrema de memória/encerramento pelo SO. `InputStream` pode bloquear; não há
timeout universal nem cancelamento preemptivo de Argon2/JCA. Uma integração futura
deverá cuidar do ciclo de vida/cancelamento sem entregar resultado após cancelamento.
Não repetir automaticamente tentativas concorrentes nem parâmetros mais fracos.
O motor verifica interrupção da thread antes da operação, durante a leitura, entre
KDF/cifra e antes de devolver plaintext. Se detectada após autenticação, limpa o buffer
privado antes de abortar. `CancellationException` de streams continua sendo cancelamento,
com mensagem fixa `CANCELLED` e sem causa original. O flag de interrupção é preservado.
Um Job de coroutine cancelado sem interromper a thread não é detectado automaticamente;
o chamador futuro precisará ligar seu ciclo de vida à execução síncrona. Continua existindo
uma janela de corrida depois da última checagem, que o chamador deve tratar.

`encryptTo` só inicia a escrita após criptografia completa. Falha/interrupção do
destino pode deixar **ciphertext parcial**; não se promete publicação atômica ou
durabilidade e não se remove nenhum arquivo. Quem publica deverá preservar versões
anteriores e validar fechamento/leitura, seguindo os guardrails. Arquivos parciais
são rejeitados pelo motor e não devem ser anunciados como backups concluídos.

## Compatibilidade futura e testes

Versão 1 fixa a codificação da senha, campos, IDs e semântica; alterações incompatíveis
exigem outra versão e um caminho de leitura explícito. Parâmetros registrados permitem
ler arquivos antigos quando o perfil de criação mudar dentro dos limites suportados.
Um perfil fora desses limites exige revisão da política, não fallback silencioso.
O payload tipo 0 continuará opaco; atribuir outro tipo não pode reinterpretar arquivos v1.
Manter fixtures congelados: mudar implementação/dependência não deve regenerá-los.

`CryptoPrimitivesTest` usa o vetor Argon2id da [RFC 9106 §5.3](https://www.rfc-editor.org/rfc/rfc9106.html#section-5.3)
e AES-256-GCM caso 14 do [documento original de McGrew/Viega, apêndice B](https://csrc.nist.rip/groups/ST/toolkit/BCM/documents/proposedmodes/gcm/gcm-spec.pdf).
Os parâmetros pequenos do vetor RFC nunca entram no perfil do motor.

`BackupCryptoTest` cobre senhas Unicode/NUL, normalização significativa, payloads de
0 até 8 MiB, instâncias independentes, novos salts/nonces, todas as posições de
truncamento dos fixtures, alteração de cabeçalho/ciphertext/tag, algoritmos/versões
desconhecidos, limites, trabalho Argon2 abusivo, admissão/OOM simulado, concorrência,
erros de streams, e ausência de saída após autenticação falhar.
O teste de máximo declarado não aloca 128 MiB somente por confiar no cabeçalho.
Também cobre destino parcialmente escrito, erro de acesso e falta de memória na
escrita: somente bytes do envelope criptografado chegam ao destino, e a cópia
incompleta não pode ser descriptografada como um arquivo válido.

`app/src/test/resources/backup-crypto/` contém fixtures vazio e binário gerados por
libargon2 de referência + Python cryptography/OpenSSL, independentes de Bouncy Castle
e JCA. O gerador offline e a senha **pública e sintética** estão junto dos fixtures.
Hashes SHA-256 estão fixados nos testes. Salt/nonce determinísticos existem somente
no gerador de testes; a produção não permite injetar RNG determinístico.

Pendências antes da integração funcional: medir pico de heap e latência no ART/API 26
e ampliar a matriz ARM64 de baixa capacidade; verificar outros provedores, OOM/cancelamento e morte do
processo; revisar UX de senha/perda de senha, publicação SAF, política automática e
restauração autenticada seguida de validação/transação. Nenhuma dessas integrações
está habilitada nesta etapa.

## Validação desta implementação

Com o JDK 21 local, `./gradlew spotlessCheck testDebugUnitTest :app:assembleDebug`
concluiu com sucesso na entrega inicial. Total inicial: **188 testes**, sem falhas,
erros ou testes ignorados; **20 testes novos** do motor em duas classes, incluindo
casos parametrizados por loops. APK debug ARM64 regenerado em
`app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`.
Na entrega inicial nenhum teste em aparelho havia sido realizado. A revisão posterior,
incluindo três regressões de cancelamento e medições reais isoladas, está registrada
em [BACKUP_CRYPTO_REVIEW_0.4.md](BACKUP_CRYPTO_REVIEW_0.4.md).

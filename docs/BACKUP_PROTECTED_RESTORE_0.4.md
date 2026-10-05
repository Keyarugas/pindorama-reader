# Restauração protegida — Pindorama 0.4.4 experimental

Base: criação manual 0.4.3 preservada no commit `a81e7983`. Desenvolvimento na branch
`pindorama/backup-protected-restore-0.4`, sem integrar à principal. Não é uma versão
aprovada para distribuição pública nem uma auditoria independente.

## Fronteiras e autenticação

A seleção existente abre `RestoreBackupScreen`. `ProtectedRestoreLoader` lê até 64 bytes
para identificar o envelope pelo conteúdo, independentemente do nome/extensão. O prefixo
reservado iniciado por `P` e cabeçalhos que conservam pelo menos seis dos oito bytes do
magic são encaminhados à validação estrita: truncamentos, versões, algoritmos e parâmetros
incompatíveis são erros, nunca uma tentativa alternativa de decodificação convencional.
Não se promete identificar um envelope cujo magic tenha sido completamente destruído;
nessa situação a validação convencional ainda precisa aceitar o conteúdo como Protobuf/GZIP.
GZIP e Protobuf sem envelope continuam no caminho convencional existente.

Reconhecer não autentica. Depois da senha, `BackupCrypto.decrypt(InputStream, password)`
valida cabeçalho e recursos, lê o corpo limitado e verifica integralmente AES-GCM com AAD
antes de devolver qualquer plaintext. Não se altera o motor, formato, perfil Argon2id,
fixtures ou modelos Protobuf. Não se usa biometria como senha de arquivo.

O plaintext autenticado passa por `ConventionalBackupReader`: limites originais de
128 MiB de entrada e 64 MiB descomprimidos, verificação GZIP e decodificação Protobuf.
A preparação verifica conversão dos memos de obras/capítulos, URLs de identificação não
vazias, identidades duplicadas, números finitos, progresso não negativo, ordens de
categorias duplicadas e os tipos das preferências permitidas. Preferências desconhecidas
não são interpretadas ou aplicadas. Não é uma validação universal de todas as semânticas
possíveis de extensões; configurações de fontes/repositórios ficam fora da importação.

Somente `PreparedProtectedRestore` validado é entregue ao aplicador. Ele fica privado no
ViewModel, fora do State observável e do estado persistido. A aplicação usa o mesmo objeto,
sem reabrir o arquivo do provedor: trocar o arquivo depois da senha não troca os dados
aplicados. Senha errada, falha de integridade, limite ou conteúdo inválido não alcançam
banco, preferências ou tarefas. Não há arquivos temporários de plaintext, relatórios com
conteúdo nem novas notificações. Erros são códigos fixos sem causas ou exceções suprimidas.

## Política de preferências: negar por padrão

A política **do fluxo protegido** está em `ProtectedRestorePreferences`. O caminho
convencional permanece legado nesta etapa; a capacidade de ler backups antigos é mantida.

Somente estas chaves Boolean podem ser importadas quando a opção de configurações está
selecionada. Tipos incorretos e duplicatas dessas chaves invalidam a preparação:

- `pref_theme_dark_amoled_key`
- `relative_time_v2`
- `display_download_badge`
- `display_unread_badge`
- `display_local_badge`
- `display_language_badge`
- `display_category_tabs`
- `display_number_of_items`
- `display_continue_reading_button`

Os consumidores dessas preferências são de apresentação. Não iniciam agendamento,
rastreamento ou downloads. Toda chave ausente dessa lista, inclusive uma chave nova
introduzida no futuro, é ignorada na importação e conserva seu valor local.

A revisão incluiu SecurityPreferences, PrivacyPreferences, BasePreferences,
NetworkPreferences, UiPreferences, SourcePreferences, TrackPreferences, LibraryPreferences,
DownloadPreferences, BackupPreferences, StoragePreferences e as preferências de Reader,
updates e upcoming. A consequência explícita da lista restrita é preservar:

- `use_biometric_lock`, `lock_app_after`, `lock_on_screen_off`, `secure_screen_v2`,
  `notification_privacy_level`, `hide_notification_content` e estados `__APP_STATE_*`.
- Sessões global e privada: são objetos locais do processo; nenhum método de desbloqueio
  é chamado pelo fluxo. Não há importação de tentativa de autenticação ou estado salvo.
- `__PRIVATE_*`, credenciais e tokens de trackers, configurações arbitrárias de fontes,
  domínios, cabeçalhos, instalador, extensões confiáveis, repositórios e chaves de assinatura.
- Incógnito, fontes ocultas, acesso NSFW, telemetria/analytics/crashlytics, logs de rede,
  DNS/DoH, user agent, diretórios e permissões de armazenamento.
- Intervalos/restrições de atualização e backup, seleção de categorias dessas tarefas,
  downloads automáticos, remoção de downloads, atualização automática de rastreamento,
  configurações de sincronização e preferências do Reader.

Fontes e repositórios não aparecem como opções restauráveis no protegido. O aplicador
recusa implicitamente esses efeitos mesmo se receber `RestoreOptions` com essas opções
habilitadas. O aviso da tela explica a preservação local; não se anuncia importação completa
de configurações. Não se apaga nem reescreve o conteúdo original do arquivo.

**Nenhuma tarefa é reagendada**: todas as configurações capazes de provocá-lo são excluídas.
Não se chama setupTask, APIs remotas de trackers, downloads ou atualização de extensões.
As tarefas que o usuário já tinha configurado permanecem como estavam; não há suspensão
ou cancelamento global desses trabalhos. Esta implementação não estabelece exclusão mútua
com workers preexistentes. Uma execução já autorizada localmente pode observar dados
confirmados entre transações; o arquivo não cria nem configura essa execução.

Dados de rastreamento das obras continuam sendo registros locais do banco, não credenciais.
Seu uso posterior por funcionalidades já configuradas localmente não é uma sincronização
iniciada pela importação. Configurar serviços e fontes continua sendo uma ação posterior.

## Aplicação, transações e falhas

`ProtectedBackupRestorer` aplica sequencialmente e para na primeira falha:

1. Revalidar preferências e verificar cancelamento.
2. Categorias via `CategoriesRestorer.restoreData`: transação existente de categorias,
   sem alterar automaticamente a preferência de exibição categorizada.
3. Obras via `MangaRestorer.restoreProtected`: uma transação por obra, incluindo capítulos,
   categorias da obra, histórico, rastreamento e scanlators. A privacidade combinada permanece
   `existente.isPrivate || importada.isPrivate`, independentemente da versão dominante.
4. Após todas as etapas de dados, aplicar somente as preferências permitidas selecionadas.
5. Verificar cancelamento e anunciar conclusão. Não há etapa de agendamento.

A entrada estrita de MangaRestorer usa repositórios que propagam erros. Evita os interactors
legados que capturam/logam exceções brutas e retornam listas vazias ou sucesso aparente.
Não recalcula o intervalo de atualização via o interactor legado que absorve falhas.
Há verificação de cancelamento no início e antes da confirmação da transação da obra.
O caminho convencional conserva seus comportamentos anteriores.

Não é uma transação integral do backup. Categorias e obras anteriores já confirmadas podem
permanecer após falha/cancelamento posterior. A transação corrente protege suas próprias
alterações; não reverte confirmações anteriores. As preferências visuais são gravadas
individualmente após os dados: uma falha entre elas pode deixar parte dessas preferências
aplicada. SharedPreferences.apply não comprova durabilidade síncrona no disco. Nenhuma dessas
falhas altera credenciais, segurança ou agendamento pela importação. A tela distingue
conclusão de interrupção com possíveis alterações parciais, sem títulos nem contagens.

Morte do processo pode ocorrer depois de um commit e antes da atualização da tela. Não há
journal de restauração, retomada automática, rollback global ou prova de recuperação após
morte do processo no Android. Uma etapa futura precisará coordenar staging/commit do banco,
journal durável de preferências e efeitos externos, concorrência com workers e recuperação
após reinício, antes de prometer atomicidade integral. Não se ampliou schema/migrations.

O fluxo não altera arquivos de downloads ou inicia reconstrução assíncrona de seu cache.
Não há senha em WorkManager. É uma operação ligada à tela; sair ou destruir a composição
cancela e descarta a preparação. Argon2/JCA/I/O bloqueado pode atrasar cancelamento.

## Interface e privacidade

Senha mascarada, com teclado de senha e rótulo acessível. `remember` sem `rememberSaveable`;
arrays mutáveis limpos ao terminar, falhar ou cancelar, inclusive antes do corpo da coroutine
começar. Não há persistência de senha. A tela explica independência do bloqueio/biometria.
Opções somente depois da autenticação e validação; nenhuma prévia de títulos, capas,
fontes ou categorias. Progresso indeterminado e mensagens genéricas, inclusive com sessão
privada bloqueada. O bloqueio global continua sendo imposto pela arquitetura existente
na Activity; o novo fluxo não a desbloqueia nem modifica configurações de segurança.

Senhas Unicode seguem exatamente a codificação do motor, sem normalização/trim. Limpeza
absoluta de Strings/Compose, objetos Protobuf, buffers Okio/JCA/Deflater e cópias da JVM/ART
não é garantida. Arrays próprios de plaintext e Protobuf são limpos quando viável.

## Recursos, Android e testes

O motor recebe stream, evitando coletar primeiro uma cópia integral do envelope no chamador.
Mantêm-se os limites do motor (128 MiB de payload, envelope com 80 bytes adicionais), sua
admissão proporcional e Argon2id 64 MiB/t=3/p=4. Antes da decodificação é exigida novamente
a admissão mínima de 144 MiB disponíveis. Ela não reserva memória nem limita o crescimento
do grafo Protobuf. A descompressão é limitada; a alocação do grafo ainda depende do decoder.
OOM recuperável produz erro genérico; encerramento pelo sistema não é recuperável em finally.

Um heap máximo de 128 MiB é recusado pelo perfil atual. O teto de formato não é promessa de
suporte a arquivos de 128 MiB em aparelhos com heap reduzido. Bibliotecas com muitos capítulos
podem ser recusadas ou pressionar memória mesmo abaixo do teto. Não há downgrade de Argon2,
aumento dos limites ou retry ilimitado. Não há descompressão antes da autenticação.

Testes usam o fixture congelado 0.4.3 e o motor existente, dados sintéticos, AndroidPreferenceStore
sobre SharedPreferences simulado e testes de aplicação com schema/repositórios SQLDelight reais
sobre SQLite JDBC em memória. A dependência sqlite-driver 2.3.2 é **somente de teste**, alinhada
à versão SQLDelight existente, conforme a
[documentação oficial](https://sqldelight.github.io/sqldelight/2.3.2/js_sqlite/multiplatform/).
Os testes JDBC são executados na mesma thread; não demonstram o comportamento do driver
Android com suspensão/mudança de thread ou morte de processo.

API mínima permanece 26. Nenhuma medição da JVM é apresentada como teste em aparelho.
Execução completa em API 26/28, Conscrypt antigo, pouca memória e provedores reais continua
pendente. Os números isolados de 0.4.2 não validam o consumo deste fluxo.

### Medição do fluxo na JVM

Uma amostra com JDK 21, heap máximo de 512 MiB, arquivo sintético retido em memória e
SQLite JDBC em memória. A preparação inclui leitura, KDF, autenticação, descompressão e
validação; aplicação usa schema e repositórios reais. Há 20 capítulos por obra e descrição
sintética de 1.024 caracteres. Heap amostrado aproximadamente a cada 1 ms, podendo perder
picos curtos; não inclui todo o consumo nativo do SQLite/JCA, não é RSS e não é benchmark
estatístico. GC é solicitado somente antes da medição de teste.

| Obras / capítulos | Arquivo bytes | Preparação ms | Aplicação ms | Heap inicial MiB | Pico MiB | Adicional MiB |
| --- | --- | --- | --- | --- | --- | --- |
| 100 / 2.000 | 68.867 | 166 | 93 | 80,4 | 161,4 | 81,0 |
| 1.000 / 20.000 | 682.657 | 216 | 691 | 81,4 | 184,6 | 103,3 |
| 5.000 / 100.000 | 3.410.494 | 314 | 7.964 | 84,4 | 233,8 | 149,5 |

Esses resultados não incluem o restante do processo Android, seu driver, UI, SAF, biblioteca
existente grande ou workers concorrentes. O teste retém o envelope original; em produção
a entrada é um stream e não exige essa cópia. Não extrapolar o tamanho do arquivo para
prever o tamanho do grafo descomprimido.

## Checklist manual pendente — somente dados sintéticos

- Criar e restaurar `.pindobk` com senha Unicode em instalações independentes.
- Renomear extensões; confirmar reconhecimento pelo conteúdo e rejeição de formatos inválidos.
- Senha errada, truncamento e adulteração: nenhuma alteração no banco/preferências/tarefas.
- Restaurar com sessão privada bloqueada: nenhuma prévia privada nem desbloqueio automático.
- Combinar obras privadas/públicas nas duas direções; privado deve sempre prevalecer.
- Manter app lock, timeouts, tela segura, notificações, tokens, fontes e tarefas locais.
- Cancelar senha, validação, aplicação; girar tela e encerrar processo. Conferir estados parciais.
- Forçar armazenamento cheio e falhas de banco; inspecionar transação corrente e obras anteriores.
- Verificar notificações/logcat/arquivos locais, sem segredos ou plaintext temporário.
- Testar `.tachibk` antigos, API 26/28 e Android recente, heaps pequenos e bibliotecas grandes.
- Conferir atividade de workers já configurados, incluindo concorrência durante aplicação.

Nenhum teste manual acima foi considerado realizado.

## Arquivos desta etapa

- `app/src/main/java/eu/kanade/presentation/more/settings/screen/data/RestoreBackupScreen.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/restore/ProtectedRestoreLoader.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/restore/ProtectedBackupRestorer.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/restore/ProtectedRestorePreferences.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/restore/restorers/CategoriesRestorer.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/restore/restorers/MangaRestorer.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/ConventionalBackupReader.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/BackupError.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/create/ProtectedBackupPayload.kt`
- `app/src/test/java/eu/kanade/tachiyomi/data/backup/ProtectedBackupRestoreTest.kt`
- `app/src/test/java/eu/kanade/tachiyomi/data/backup/ProtectedRestoreDatabaseTest.kt`
- `app/src/test/java/eu/kanade/tachiyomi/data/backup/ProtectedRestoreViewModelTest.kt`
- `app/build.gradle.kts` e `gradle/libs.versions.toml` — driver SQLite somente para testes.
- `i18n/src/commonMain/moko-resources/base/strings.xml` e `pt-rBR/strings.xml` — interface,
  mensagens genéricas e atualização dos avisos da criação.
- Este documento. Nenhum fixture congelado foi regenerado ou alterado.

## Validação automatizada final

JDK 21 local: `spotlessCheck`, `testDebugUnitTest` e `:app:assembleDebug` aprovados.
233 testes, incluindo 22 novos, sem falhas, erros ou ignorados. Os testes de criação,
vetores criptográficos, fixtures congelados e leitura convencional continuam passando.
APK gerado: `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`.
A medição acima corresponde à execução completa final; uma execução isolada anterior
teve pico menor (~197 MiB para 5.000 obras), mostrando a influência da ordem da suíte
sobre o heap ocupado. Nenhuma dessas amostras mede o processo Android.

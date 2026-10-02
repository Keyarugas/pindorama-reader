# Coordenação da restauração — implementação parcial

> Atualização: a rodada localizada de migração/Reader está documentada em
> [BACKUP_RESTORE_READER_MIGRATION_0.4.md](BACKUP_RESTORE_READER_MIGRATION_0.4.md).
> O texto abaixo registra o estado anterior; a restauração continua desconectada.

## Estado anterior à rodada Reader/migração

A alternativa de recusa conservadora foi aprovada. Ainda **não está habilitada**
no fluxo real: o coordenador não foi conectado ao restaurador ou aos escritores.
Não interpretar os testes do protótipo como demonstração de coordenação do app.
As limitações de concorrência da 0.4.4.1 permanecem.

Foi implementada a separação das preferências visuais na interface protegida:

- `appSettings` fica desativado após autenticação. O padrão convencional não mudou.
- A biblioteca é restaurada com `appSettings = false`.
- Se explicitamente selecionadas, as nove preferências são importadas em uma chamada
  separada, após o retorno bem-sucedido da fase de biblioteca.
- A tela distingue preferências não selecionadas, pendentes, concluídas e com falha.
  A confirmação da biblioteca permanece visível quando as preferências falham.
- A allowlist, a compensação existente e a exclusão de segurança, credenciais e tarefas
  continuam inalteradas. Não se reagendam Workers.
- Não há registro durável desses resultados de interface. Morte do processo na fronteira
  de COMMIT ainda exige revisão; não há atomicidade entre SQLite e preferências.

## Protótipo isolado de admissão

`LibraryOperationCoordinator` admite operações participantes simultâneas e oferece
exclusividade com drenagem por até 30 segundos. Durante a drenagem, novas operações
participantes aguardam. Falha, cancelamento e timeout liberam a admissão em `finally`.
A espera ocorre antes de qualquer transação. Chamadas aninhadas herdam a participação
no contexto da coroutine. Não há persistência, cancelamento de Workers ou `retry`.

O protótipo NÃO descobre operações automaticamente. Todos os produtores e consumidores
devem participar antes de ler o estado que fundamentará suas escritas. A fila deverá
ser inspecionada dentro da exclusividade, sem mutação, antes da transação.

## Fronteiras ainda não resolvidas

Dois contraexemplos executáveis impedem conectar esse protótipo como se bastasse:

1. `MigrationListViewModel` inicia trabalho com `migrationScope.async` e o aguarda em
   outra coroutine. Cancelar o aguardador não implica terminar o produtor independente.
   Coordenar somente o aguardador permite que a exclusividade seja liberada enquanto
   o produtor continua. O produtor precisa possuir sua própria participação, incluindo
   leitura, rede e efeitos finais, sem depender do tempo de vida do aguardador.
2. `ReaderViewModel.updateChapterProgress` aplica campos de `ReaderChapter` mantidos
   em memória. Uma exclusividade entre dois callbacks não atualiza esse snapshot.
   Um `read = false` antigo pode sobrescrever `read = true` restaurado. É necessário
   invalidar/recarregar snapshots ou distinguir intenção explícita de valores incidentais
   antigos antes de aplicar a operação. Repetir a escrita com o mesmo objeto não basta.

Esses casos são anteriores à integração, não regressões introduzidas pelo protótipo.
Os testes reproduzem a semântica com coroutines e valores sintéticos, NÃO executam os
ViewModels de migração/Reader reais. Ainda não demonstram uma correção nesses componentes.

Para concluir com garantia confiável é necessário adaptar o tempo de vida dos produtores
independentes e os contratos das operações que recebem snapshots, além de integrar e
testar todos os escritores/filas. Essa adaptação permanece pendente; não se declarou
impossibilidade técnica nem foi introduzido um protocolo persistente alternativo.

Proposta concreta para essa fronteira: cada produtor independente deve deter participação
própria até terminar, mesmo se seu aguardador for cancelado. Para snapshots retidos, usar
uma geração somente em memória e reconsultar identidade/estado antes de aplicar intenções;
uma geração nova não pode justificar descarte silencioso de uma ação do usuário. Se não
houver revalidação inequívoca, bloquear a restauração enquanto existir o consumidor do
snapshot é a alternativa conservadora, mas pode exigir fechar Reader/telas de migração.
Essas estratégias ainda não foram implementadas nem demonstradas nos componentes reais.

A recusa de uma fila persistida sem identidade verificável pode durar indefinidamente se
suas operações nunca concluírem (por exemplo, serviço indisponível ou credenciais inválidas).
A solução não deve apagar a fila ou executar tarefas para liberar a restauração. Reparar
a configuração do serviço pelo fluxo normal ou projetar posteriormente a reconciliação
explícita são alternativas; a segunda permanece fora desta etapa.

## Cobertura e validação manual pendentes

Os testes novos verificam admissão/drenagem, timeout, cancelamento, liberação, chamadas
aninhadas e os dois contraexemplos. Outros testes verificam a seleção explícita e os
resultados das preferências no ViewModel real com backend simulado, e o aplicador real
de preferências sobre o armazenamento de teste existente.

Ainda faltam testes integrados de recusa de filas, produtores concorrentes reais,
reinício de Workers, Reader, categorias e migração. Não foram instalados aplicativos
ou utilizados dados do dispositivo. Não há alteração de schema/consultas SQL; os testes
de rollback existentes devem continuar passando, mas não comprovam a coordenação.

Checklist manual, exclusivamente em instalação isolada com dados sintéticos:

- Confirmar que preferências visuais começam desmarcadas em um backup protegido.
- Sem seleção, verificar que a biblioteca é importada e as preferências locais permanecem.
- Com seleção, verificar resultado separado e preservação das configurações de segurança.
- Simular falha na aplicação das preferências e verificar ausência de sucesso integral.
- Não validar a recusa/coordenação como implementadas: essas integrações ainda estão pendentes.

## Investigação anterior (histórico)

Validação do estado parcial atual com JDK 21: `spotlessCheck`, `testDebugUnitTest`
e `:app:assembleDebug` passaram; 248 testes, zero falhas/erros/ignorados. São nove
testes adicionais (sete do protótipo/contraexemplos e dois das preferências), não
uma demonstração das integrações de coordenação ainda pendentes. `git diff --check`
passou. APK: `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`.
Sem commit, merge, push, instalação no dispositivo ou validação manual.

Estado da investigação: implementação suspensa para decisão sobre tarefas persistidas.
Base: `897e320e0491fe546be446b38670565c4ba90dd8`.
Branch: `pindorama/backup-restore-coordination-0.4`.

## Evidência concreta

`DelayedTrackingStore` persiste somente `trackId -> lastChapterRead` em
SharedPreferences. Não registra a identidade remota à qual o progresso pertencia.
`DelayedTrackingUpdateJob` resolve esse ID no banco atual e chama `TrackChapter`,
que consulta os vínculos atuais da obra antes de enviar o progresso ao serviço.

`MangaRestorer.restoreTracking` combina vínculos pelo trackerId. Para um vínculo
existente, conserva o ID local, mas permite atualizar remoteId e libraryId a partir
do backup. Portanto, a validade do ID local não demonstra a validade da intenção
pendente após restauração.

Reprodução sintética executada com SQLite Python em memória, extraindo o CREATE TABLE
e as consultas insert/update de `data/src/main/sqldelight/tachiyomi/data/manga_sync.sq`:

1. Vínculo local 1, destino remoto 10, progresso atual 1.
2. Intenção pendente representável pela fila existente: ID local 1, progresso 5.
3. UPDATE utilizado pela restauração: mesmo ID local, destino remoto 20, progresso 2.
4. A consulta pelo ID pendente continua válida e retorna destino 20. O progresso
   pendente 5 supera o progresso atual, mas foi originado para destino 10.

O worker pode enviar essa intenção ao destino novo. Após morte do processo, um
contador de geração apenas em memória não permite recuperar a identidade anterior.
O experimento confirma o estado SQL; não executou rede, WorkManager, aplicativo
Android ou um teste integrado do restaurador. Nenhum dado pessoal foi utilizado.

Uma sonda inicial também demonstrou troca de ID com INSERT/REPLACE, usado pelo
repositório de rastreamento. Esse NÃO é o caminho de atualização de vínculos
existentes no restaurador; o impedimento acima usa UPDATE e não depende de REPLACE.

## Filas de arquivos também exigem cuidado

`DownloadPendingDeleter.getPendingChapters` limpa a fila persistida antes de
`DownloadManager.deletePendingChapters` executar as exclusões. Morte do processo
nessa janela pode perder trabalho pendente. `DownloadStore.restore` também limpa
o armazenamento antes da reinserção das tarefas reconstruídas. São comportamentos
existentes, não regressões introduzidas nesta investigação.

Uma coordenação em memória pode impedir concorrência durante a vida do processo;
ela não transforma essas sequências em operações recuperáveis após encerramento.
Não se propõe apagar filas, consumir tentativas de Workers nem alegar atomicidade
entre banco, SharedPreferences, arquivos e serviços externos.

## Alternativas para aprovação

### A — Recusa conservadora de conflitos persistidos

Após autenticar/validar e obter exclusividade, verificar intenções persistidas e as
mudanças de vínculos pretendidas antes de abrir a transação de restauração. Recusar
a restauração inteira se não for possível provar a compatibilidade. Não remover
intenções ou omitir silenciosamente dados selecionados. A mensagem deve ser genérica.

A verificação e todos os produtores/consumidores das filas devem compartilhar a
coordenação. Filas antigas sem identidade suficiente precisam de tratamento
conservador. Essa opção limita quando a restauração pode ocorrer, mas evita um novo
journal de recuperação. Não corrige as janelas preexistentes de perda das filas de
arquivos; essa limitação deve permanecer explícita e testada separadamente.

### B — Identidade persistida e reconciliação de intenções

Versionar a representação das intenções com identidade estável suficiente, incluindo
destino remoto para rastreamento. Revalidar antes de executar; preservar conflitos
para decisão explícita, sem reenviá-los a outro destino. Planejar migração das filas
antigas, cuja identidade anterior nem sempre pode ser inferida com segurança.

Para garantir preservação de tarefas de arquivos após reinício, substituir remoção
antecipada por confirmação individual e execução idempotente. É necessário projetar
as fronteiras de persistência e testar interrupções em cada uma. Isso amplia a
arquitetura das filas; não foi implementado sem aprovação. Não implica garantia de
exactly-once para efeitos remotos.

## Próximos testes

Além dos testes de admissão, drenagem, timeout, cancelamento e rollback solicitados:

- Intenção anterior de rastreamento com vínculo remoto alterado pelo backup.
- Reinício entre COMMIT da restauração e retomada do worker.
- Filas antigas sem identidade suficiente e conflitos preservados.
- Interrupção entre leitura de fila, execução e confirmação individual.
- Nenhum envio de progresso a um vínculo diferente da intenção original.

As nove preferências visuais continuam inalteradas nesta pausa. A separação da
importação com padrão desativado e resultados independentes ainda será implementada.
Nenhum coordenador ou mecanismo persistente foi adicionado. Os checks Gradle não
foram repetidos nesta investigação: não houve alteração de código; os 239 testes
informados anteriormente não validam a coordenação proposta.

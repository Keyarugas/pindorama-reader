# Coordenação 0.4 — produtores da migração e snapshots do Reader

> Atualização localizada: a aplicação SQLite da restauração protegida agora usa
> exclusividade contra os participantes já adaptados. A geração é publicada ao final,
> antes de liberar a admissão, inclusive após falha/cancelamento da aplicação; timeout
> ou cancelamento durante drenagem não a alteram. Preferências visuais ficam fora dessa
> exclusividade. Isso NÃO representa cobertura global de escritores, Workers ou filas.
> O restante deste documento registra a rodada anterior, quando a restauração estava desconectada.

Branch: `pindorama/backup-restore-coordination-0.4`.
Base preservada: `897e320e0491fe546be446b38670565c4ba90dd8`.
Esta rodada preserva as preferências visuais opcionais, os testes anteriores e o
protótipo. **Não conecta a restauração protegida ao coordenador.**

## Migração: o produtor possui a participação

Antes, MigrationListViewModel aguardava um Deferred criado em migrationScope. Cancelar
esse aguardador não encerrava necessariamente o produtor, e um bloqueio adquirido pelo
aguardador seria liberado antes das escritas do produtor. O SupervisorJob de cada item
também não estava ligado ao Job do ViewModel.

Agora MigratingManga.produce usa libraryAsync: a admissão é adquirida dentro da coroutine
produtora e mantida por um coroutineScope até terminarem suas filhas. A espera pelo
Deferred não é dona dessa participação. Cancelar o aguardador não libera o produtor;
cancelar o produtor/escopo pai encerra suas filhas cooperantes e libera a participação
somente quando o corpo termina. O escopo continua com SupervisorJob e Dispatchers.Default,
mas seu Job é filho do Job do ViewModel. Nenhum Worker ou escopo externo é cancelado.

As duas entradas de produtores da lista (busca automática e seleção manual) usam produce.
A busca reconsulta a obra depois da admissão e verifica ID, fonte e URL antes de usar seu
título na pesquisa. O complemento de metadados também possui participação própria e
reconsulta a identidade. Leituras puras de apresentação não recebem bloqueios de escrita.

MigrateMangaUseCase participa de ponta a ponta: reconsulta origem/destino, verifica suas
identidades, executa a atualização remota, capítulos, categorias, rastreamento e efeitos
locais até a gravação final. Usa os valores atuais de privacidade e flags, preservando a
regra existente `origem.isPrivate || destino.isPrivate`. Não reutiliza esses campos do
snapshot da tela. Os chamadores por lista e diálogo recebem resultado Boolean explícito;
recusa/falha não dispara a indicação normal de migração concluída. Cancelamento continua
sendo propagado. A mensagem de falha é genérica e admite alterações parciais anteriores.

Foi encontrado outro produtor destacado: DownloadManager.deleteManga lançava GlobalScope
via launchIO. A variante suspensa deleteMangaAndWait contém a mesma operação e é aguardada
pela migração quando REMOVE_DOWNLOAD foi escolhido. Os demais chamadores continuam usando
a entrada assíncrona anterior. Isso altera a posse/duração dessa operação da migração,
não o formato dos downloads ou a política de exclusão escolhida pelo usuário.

Não há rollback da migração inteira: capítulos/categorias já confirmados, rede ou exclusão
de arquivos podem permanecer após falha posterior. Resultado false não significa ausência
de efeitos. Tampouco se promete desfazer uma requisição já recebida pelo serviço remoto.
Uma operação não cooperante que demora a terminar mantém sua participação; a exclusividade
futura deve expirar pelo timeout, não assumir que o trabalho terminou.

## Reader: geração, identidade e intenções restritas

O coordenador mantém uma geração somente em memória, incrementada quando a exclusividade
é efetivamente obtida, antes do bloco exclusivo. Timeout durante a drenagem não incrementa.
Mesmo uma falha dentro do bloco exclusivo invalida a geração anterior conservadoramente.
A geração não é salva em preferências, banco, SavedState ou arquivo.

Cada ReaderViewModel captura a geração de sua sessão. ReaderWriteGuard verifica a geração
depois de obter admissão, e reconsulta a identidade da obra (ID + fonte + URL) e do capítulo
(ID + obra + URL). Reusar um ID para outra identidade ou mover um capítulo para outra obra
não autoriza uma escrita. Geração antiga é recusada; o Reader não a atualiza automaticamente
para aplicar o mesmo snapshot antigo. A mensagem orienta reabrir a leitura.

Para progresso, bookmark e histórico, admissão ocorre antes da transação. Validação,
consulta do estado atual e gravação ficam na mesma transação SQLite, com verificação de
cancelamento antes de encerrá-la:

- Progresso grava somente a página visitada e `readAtual || chegouAoFim`. Não grava bookmark,
  classificação privada ou outros campos do snapshot. Navegar normalmente para uma página
  anterior continua permitido em uma sessão válida; não se impõe máximo artificial à página.
- Duplicatas são consultadas no banco atual, sem usar a antiga lista lazy do Reader.
- Bookmark alterna o valor atual do banco. O ícone só muda após confirmação da escrita e
  somente se o capítulo ainda for o exibido.
- Histórico usa a query existente diretamente: o repositório legado absorvia exceções SQL,
  impedindo distinguir falha de gravação de sucesso. O timer não é confirmado como salvo se
  a gravação falhar. Nenhuma query, schema ou migration foi alterada.
- Flags do visualizador preservam os bits atuais não envolvidos na intenção. Uma falha
  informada pelo repositório não é considerada sucesso. A alteração do modo de leitura
  deixou de bloquear a thread principal enquanto espera a coordenação.

Rastreamento disparado pelo Reader, inclusão de capítulos lidos na fila de exclusão e
edição de capa também revalidam geração/identidade antes do efeito, sob participação própria.
Não mantêm uma transação SQLite aberta durante rede ou arquivos. Um efeito adiado de uma
sessão invalidada é recusado; não há reenvio automático com o snapshot antigo.

A posição visual da página continua podendo mudar antes de uma gravação: mostrar a página
não significa que seu progresso foi persistido. O modelo persistível e o bookmark não são
atualizados otimisticamente após uma recusa. Erros de escrita usam mensagem fixa sem títulos,
URLs, IDs remotos ou exceções brutas na interface.

## Testes e alcance das provas

ReaderWriteGuardTest usa schema/queries gerados e SQLite JDBC reais. Cobre geração antiga,
progresso/bookmark/histórico/flags preservados após recusa, nova sessão válida, identidades
de obra/capítulo alteradas, capítulo removido ou transferido, callback aguardando exclusividade,
cancelamento durante admissão e falhas SQL de progresso/histórico. Verifica preservação de
isPrivate. A rede em chapterEffect é simulada para verificar a duração da participação.

MigrationCoordinationTest usa MigratingManga.produce real com respostas sintéticas:
cancelamento do aguardador, filhas ainda ativas, admissão durante exclusividade, falha do
produtor e cancelamento do pai. MigrateMangaCoordinationTest usa o caso de uso real com
repositórios/rede/arquivos simulados: identidade alterada, dados atuais de privacidade/flags,
resposta tardia, falha, cancelamento e espera da exclusão opcional dos downloads.

Esses testes não exercitam a UI Android completa nem provam o driver Android. Os testes
SQLite anteriores de restauração/rollback permanecem na suíte completa. Os contraexemplos
anteriores continuam no repositório como demonstração do padrão incorreto, acompanhados
agora de regressões positivas nos componentes corrigidos.

## Estado do coordenador e operações ainda fora da proteção

Não há chamada de exclusive no restaurador. As exclusividades desta rodada são executadas
somente nos testes. A restauração real, portanto, ainda não invalida sessões do Reader nem
aguarda migrações. Não anunciar proteção completa contra restauração concorrente.

Permanecem pendentes, entre outros:

- LibraryUpdateJob, MetadataUpdateJob e chamadas de UpdateMangaFromRemote fora da migração.
- Rastreamento iniciado fora do Reader/migração, DelayedTrackingUpdateJob e inspeção atômica
  da fila persistida dentro da futura exclusividade.
- Escritores das telas da biblioteca, categorias, manutenção/limpeza e restauração convencional.
- Downloaders, downloads antecipados do Reader, cancelamento/reinserção de downloads ao
  navegar/sair do Reader e execução global das exclusões pendentes. Não foram coordenados
  globalmente. A espera da exclusão opcional na migração não resolve esses outros caminhos.
- Publicação/invalidação de estado visual após a futura restauração, inclusive conteúdo
  já apresentado e proteção de telas privadas. Rejeitar escritas não remove páginas já exibidas.

A geração só trata sessões em memória. Reinício, SavedState, retomada de Workers persistidos,
filas sem identidade completa e efeitos externos precisam de testes/regras próprios antes
da conexão final. Não há protocolo distribuído, geração persistida ou reconciliação nova.

## Viabilidade objetiva

As duas correções foram localizadas: um contrato de produtor, um guard de escritas do Reader,
reconsulta em pontos de execução e uma variante aguardável de operação já existente.
Não exigiram migração do banco nem reestruturação geral da persistência.

A coordenação conservadora restante parece viável incrementalmente com adaptações localizadas
por família de operações, mas a cobertura é transversal: não basta conectar exclusive no
restaurador. Cada produtor deve admitir antes de ler/processar e manter participação até o
último efeito; APIs destacadas, como a exclusão encontrada nesta rodada, precisam de variantes
aguardáveis ou participação própria. A inspeção das filas deve compartilhar esse protocolo.
Ainda não há prova de cobertura de todos os escritores, e não se deve estimar a integração
como uma alteração de um único arquivo.

Garantias de atomicidade entre banco/arquivos/preferências/serviços remotos ou preservação
universal de intenções após morte do processo exigiriam arquitetura adicional. Continuam
fora do escopo. Para a política aprovada de recusa conservadora, não foi demonstrada nesta
rodada necessidade de versionar persistentemente filas ou alterar o schema.

## Checklist manual pendente (instalação isolada, dados sintéticos)

- Ler páginas, concluir capítulo, alternar bookmark, abrir outro capítulo e verificar o
  progresso/histórico após reabrir o app; conferir incognito e duplicatas.
- Alterar modo/orientação; confirmar ausência de bloqueio da UI e preservação de outras flags.
- Executar busca/migração, cancelar a espera e sair da tela; verificar ausência de efeitos
  depois que os produtores tiverem terminado, inclusive com resposta de fonte lenta.
- Testar falha de rede e a opção de remover downloads sintéticos; falha não deve indicar sucesso.
- Em harness de teste, obter exclusividade enquanto o Reader está aberto: callbacks antigos
  devem ser recusados e uma nova sessão deve funcionar. O fluxo real de backup NÃO dispara
  essa exclusividade nesta rodada.
- Validar API 26 e driver Android com suspensões. Nenhum dispositivo foi modificado ou testado
  nesta rodada; resultados JVM não são validação em aparelho.

## Arquivos desta rodada

- `core/common/.../LibraryOperationCoordinator.kt`: geração e produtor libraryAsync.
- `app/.../ui/reader/ReaderWriteGuard.kt` (novo), `ReaderViewModel.kt`, `ReaderActivity.kt`.
- `app/.../migration/list/models/MigratingManga.kt`, `MigrationListViewModel.kt`,
  `MigrationListScreen.kt`, `migration/usecases/MigrateMangaUseCase.kt`,
  `migration/dialog/MigrateMangaDialog.kt`.
- `app/.../data/download/DownloadManager.kt`: variante aguardável para a migração.
- Strings base/pt-rBR: mensagens genéricas de recusa/falha.
- Testes novos `ReaderWriteGuardTest.kt`, `MigrationCoordinationTest.kt`,
  `MigrateMangaCoordinationTest.kt`.
- Este documento e referência de atualização no documento anterior de coordenação.

As alterações anteriores das preferências visuais, testes e documentação foram preservadas.
Sem mudanças em schema/queries, fixtures, Protobuf, formato, criptografia ou parâmetros.
Sem commit, merge, push, publicação ou instalação em aparelho.

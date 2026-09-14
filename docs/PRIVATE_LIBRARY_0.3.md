# Biblioteca Privada — primeira fase do Pindorama 0.3

## Auditoria e escopo

Base: `8634773dc`, `pindorama/main`, sincronizada com `origin/main`.

- Domínio: `domain/.../manga/model/Manga.kt`, `MangaUpdate.kt` e `MangaRepository`.
  Favorite, categorias, viewer/chapter flags, notes e memo têm outras semânticas;
  nenhum deles foi reutilizado como classificação de privacidade.
- Persistência: `data/.../data/mangas.sq`, `MangaRepositoryImpl`, `MangaMapper`.
  A última migration anterior era `14.sqm`. `libraryView` seleciona `M.*`.
- Biblioteca: `GetLibraryManga` e `LibraryViewModel`; categorias são associações
  separadas, sem alteração de schema ou comportamento de categorização.
- Histórico/updates: `GetHistory`, `GetUpdates`, respectivas views SQL e ViewModels.
- Home: `PindoramaHomeViewModel`, com seleção de leitura, updates e atividade.
- Pesquisa: filtros locais de biblioteca/histórico; `GlobalSearchViewModel` e
  `SearchViewModel` consultam fontes remotas, que permanecem fora do filtro local.
- Detalhes: `ui/manga/MangaScreen` e `MangaViewModel`; apresentação e toolbar nas
  variantes de telefone/tablet. Entradas pela Main já aguardam a PrivacySession.
- Outras listas locais: seleção de obras para migração e próximos capítulos.
- Backup: `MangaBackupCreator`, `BackupManga`, `MangaRestorer`; Protobuf comprimido,
  sem criptografia. Não havia campo equivalente de privacidade.
- Notificações: política central existente, notifiers de biblioteca/download,
  notificações agrupadas e conteúdos sem identificação de obra.

## Schema e domínio

`15.sqm` adiciona somente:

```sql
ALTER TABLE mangas ADD COLUMN is_private INTEGER NOT NULL DEFAULT 0;
```

O schema SQLDelight tipa a coluna como Boolean. `Manga.isPrivate` tem default false;
`MangaUpdate.isPrivate` é nullable para atualizações parciais. Mappers e repositório
transportam a coluna. A ação de detalhes atualiza somente essa classificação;
triggers herdados de timestamps continuam funcionando. Não altera favorite,
capítulos, categorias ou downloads.

Não existe infraestrutura de testes/snapshots de migration no módulo `data`.
Foi feita verificação SQLite em memória, usando o CREATE TABLE e índices da base
anterior e executando `15.sqm`: registro preservado, defaults false para registros
existentes e novos, flag gravável, índices preservados e integrity_check=ok.
A atualização de um banco real existente ainda precisa de teste no aparelho.

## Sessões independentes e política de visibilidade

A implementação inicial desta branch usava a PrivacySession global nos filtros.
Isso tornava todas as obras visíveis ao desbloquear o app e não permitia ocultá-las
com app lock desativado. A integração foi substituída por duas autorizações:

- `PrivacySession` / `PrivacySessionManager`: bloqueio do aplicativo inteiro,
  configuração e timeout global herdados. UNLOCKED aqui não libera conteúdo privado.
- `PrivateContentSession` / `PrivateContentSessionManager`: estado exclusivamente
  em memória, StateFlow com LOCKED, AUTHENTICATING e UNLOCKED. Sempre nasce LOCKED,
  independentemente das preferências globais. Nenhum UNLOCKED é gravado em disco.

A sessão privada bloqueia no ON_STOP do processo e em SCREEN_OFF, sem novo timeout
ou preferência. O ProcessLifecycleOwner tem a pequena tolerância herdada para
transições/rotação; não se trata de um cronômetro configurável. A passagem pela tela
de credenciais do Android durante autenticação não cancela a tentativa por si só.
SCREEN_OFF invalida até tentativas em andamento. Cancelamento, erro e callbacks
atrasados nunca liberam a sessão; cada tentativa tem uma geração invalidável.

Biblioteca > menu de três pontos oferece **Mostrar conteúdo privado** ou
**Ocultar conteúdo privado**. O segundo bloqueia imediatamente a sessão privada.
O primeiro usa o mesmo UnlockActivity e AuthenticatorUtil de biometria/credencial do
Android. AuthenticationTarget captura a sessão a autorizar, sem criar outro sistema
de autenticação ou PIN. Sem credencial disponível, o conteúdo permanece oculto.

Com app lock OFF, o aplicativo público abre sem prompt e privadas ficam ocultas.
Com app lock ON, primeiro é exigida a autenticação global; privadas continuam ocultas
até uma solicitação própria. Não há desbloqueio implícito entre as sessões.

`MangaVisibilityPolicy` aceita somente `PrivateContentSessionState`:

- Pública: visível em qualquer estado da sessão privada.
- Privada: visível somente em UNLOCKED; LOCKED e AUTHENTICATING ocultam.

`PrivateContentVisibility` combina IDs privados do repositório com essa sessão.
Até carregar a classificação, emite lista vazia. Não modifica consultas gerais
usadas por tarefas, sincronização ou backups. Os fluxos de apresentação continuam
observando a política enquanto o ViewModel existe, evitando caches de abas ocultas
que conservem a autorização anterior.

Cobertura: biblioteca antes de contagens/categorias/pesquisa, histórico, Updates,
Home antes de seleção/limites/métricas, migração e próximos capítulos. Diálogos,
seleções e consultas locais de biblioteca/histórico são descartados ao bloquear.
O cadeado acessível permanece na biblioteca quando privadas estão visíveis.

Estatísticas combinam biblioteca, classificação e sessão na mesma emissão; duração
de leitura exclui registros privados na consulta agregada. Fila de downloads e seu contador na aba Mais filtram
linhas/cabeçalhos; reordenação das linhas visíveis preserva downloads ocultos e suas
posições. Controles globais de iniciar/pausar/limpar fila mantêm o comportamento
herdado, inclusive para tarefas ocultas. Nada é renomeado ou movido no armazenamento.

MangaScreen verifica a política antes de compor capa, título, capítulos e diálogos.
`PrivateContentGate` mostra uma página neutra e solicita a autenticação privada.
O destino permanece no navigator; sucesso revela a obra original, cancelamento
mantém o portão neutro com tentativa explícita ou volta à tela anterior. A fila
`PendingPrivacyIntents` da Main continua responsável somente pelo bloqueio global.
Marcar uma obra enquanto a sessão privada está bloqueada fecha seus detalhes após
a atualização; remover privacidade exige que a obra já esteja acessível.

Sem editar o Reader, o SecureActivityDelegate verifica o mangaId de seu Intent antes
de liberar a janela; uma entrada privada aguarda a autenticação privada e conserva
os extras originais. Cancelar fecha essa Activity. IDs ainda não carregados são
tratados conservadoramente. Validar caminhos e transições reais no aparelho.

Recent Apps: o delegate oculta a janela ao pausar quando há obras privadas e usa
FLAG_SECURE durante a ocultação. No Android 13+, desabilita também a captura de
preview enquanto houver qualquer obra privada. Essa opção conservadora pode ocultar
o preview mesmo de uma tela pública. A preferência de screenshots continua separada;
conteúdo privado desbloqueado pode ser capturado se a proteção de tela estiver OFF.

## Notificações

Nível efetivo = nível global, elevado a PRIVATE quando há conteúdo privado,
independentemente da sessão estar desbloqueada. Notifiers de biblioteca e download
anexam IDs técnicos e, quando disponível, a flag do domínio antes de build().
Resumo de updates misto também recebe PRIVATE. A reconstrução existente remove
textos identificáveis, imagens, contagens, layouts e extras arbitrários.

Quando não há atribuição de obra, canais que podem conter dados locais usam PRIVATE
se houver obras privadas ou se a classificação ainda não tiver carregado. Isso pode
neutralizar também notificações públicas: é uma escolha conservadora, incluindo
imagens salvas sem modificar o Reader e notificações antigas sem IDs.

Mudanças de classificação reaplicam a política às notificações ativas. Uma notificação
já neutralizada não recupera conteúdo detalhado automaticamente ao remover privacidade;
o próximo evento pode reconstruí-lo. Histórico de notificações do Android, capturas
anteriores e listeners externos não são apagados retroativamente.

Ações e PendingIntents existentes são preservados. Broadcasts de tarefas e ações
que abrem apps externos (galeria, arquivo de erros, compartilhar backup) não ganham
um novo portão de autenticação nesta fase. A política protege a apresentação da
notificação, não revoga arquivos ou permissões previamente compartilhados.

## Backup, migração entre fontes e limites

BackupManga usa o campo Protobuf opcional 1000, default false. Criação, serialização,
restauração nova e atualização existente preservam privacidade. Ao combinar registros,
privado prevalece: restaurar backup antigo não torna uma obra privada pública.
A remoção explícita continua disponível na tela da obra. Migração entre fontes
preserva privacidade se origem ou destino forem privados.

Backups podem conter títulos, histórico, classificação privada e outros dados em
formato **não criptografado** (compressão não é criptografia). Versões anteriores e
outros clientes podem ignorar/perder o campo. Não se promete preservação em round trips
por aplicativos que desconhecem essa extensão do formato.

Banco, caches, capas e downloads não foram criptografados. Pastas/nomes de arquivos
podem revelar títulos para quem tenha acesso ao armazenamento. Nada foi renomeado,
movido ou apagado automaticamente. Categorias não têm classificação privada própria.
A mesma obra pode continuar aparecendo em buscas remotas; isso não autoriza abrir
seu registro local privado sem satisfazer a sessão.

A classificação não protege contra root, sistema comprometido, extensões executando
código no processo, inspeção de arquivos/backups ou dados já enviados a fontes/trackers.
Não é uma fronteira criptográfica nem uma nova autenticação.

## Testes e validação manual

Testes automatizados:
- Visibilidade pública/privada em LOCKED, AUTHENTICATING e UNLOCKED.
- Biblioteca, histórico, updates e mudanças observáveis de classificação/sessão.
- Home: seleção, updates e atividade sem as obras privadas.
- Independência entre as sessões com app lock ON/OFF, inclusive timeout global Nunca.
- Autenticação privada, cancelamento/erro, geração de callbacks e processo reiniciado.
- Background, exceção da credencial Android, screen off e destino pendente.
- Pesquisa local e reordenação de downloads sem perda das linhas ocultas.
- Notificação privada permanece PRIVATE inclusive com sessão privada UNLOCKED; fallback seguro.
- Conversão real de backup, round trip Protobuf e ausência do campo em backups antigos.

Checklist no aparelho:
1. Atualizar o APK sobre o banco existente e conferir biblioteca/histórico/downloads.
2. Marcar/desmarcar uma obra; conferir favorite, categorias e downloads preservados.
3. Verificar cadeado em lista e nos dois grids, temas claro/escuro e TalkBack.
4. Com app lock OFF, conferir públicas acessíveis e privadas ocultas. Usar Mostrar/
   Ocultar conteúdo privado e conferir biblioteca, Home, métricas, histórico, Updates,
   busca, migração, próximos capítulos, estatísticas e fila de downloads.
5. Com app lock ON, autenticar globalmente e confirmar privadas ainda ocultas;
   só a segunda autenticação explícita deve liberá-las.
6. Abrir detalhes por notificação, atalho e pesquisa; cancelar e tentar novamente;
   verificar destino original e ausência de flashes, inclusive rotação e diálogos.
7. Com notificações globais NORMAL, gerar updates privados e mistos, download,
   pausa/erro e imagem salva; conferir shade, expansão, imagens e lock screen.
8. Marcar privada uma obra com notificação já publicada e conferir neutralização.
9. Exportar/restaurar backup privado em instalação de teste; restaurar backup antigo
   sobre registro privado deve conservar privacidade. Não usar dados únicos sem cópia.
10. Conferir migração entre fontes de obra privada em biblioteca de teste.
11. Conferir background, screen off, force-stop, rotação no prompt, cancelamento,
    credencial PIN do Android, tentativa falha e ausência de loops/flashes. Testar
    retorno muito rápido, observando a tolerância do lifecycle do processo.
12. Comparar Recent Apps e screenshots com proteção de tela ON/OFF, com e sem app lock.
13. Confirmar que a busca remota continua funcionando sem autorizar o registro local.
14. Com fila mista, reordenar somente públicas e confirmar privadas preservadas.

## Arquivos alterados nesta branch

Inclui a implementação inicial ainda não commitada e a separação das sessões.

- [app/src/main/java/eu/kanade/presentation/library/components/LibraryBadges.kt](../app/src/main/java/eu/kanade/presentation/library/components/LibraryBadges.kt)
- [app/src/main/java/eu/kanade/presentation/library/components/LibraryComfortableGrid.kt](../app/src/main/java/eu/kanade/presentation/library/components/LibraryComfortableGrid.kt)
- [app/src/main/java/eu/kanade/presentation/library/components/LibraryCompactGrid.kt](../app/src/main/java/eu/kanade/presentation/library/components/LibraryCompactGrid.kt)
- [app/src/main/java/eu/kanade/presentation/library/components/LibraryList.kt](../app/src/main/java/eu/kanade/presentation/library/components/LibraryList.kt)
- [app/src/main/java/eu/kanade/presentation/library/components/LibraryToolbar.kt](../app/src/main/java/eu/kanade/presentation/library/components/LibraryToolbar.kt)
- [app/src/main/java/eu/kanade/presentation/manga/MangaScreen.kt](../app/src/main/java/eu/kanade/presentation/manga/MangaScreen.kt)
- [app/src/main/java/eu/kanade/presentation/manga/components/MangaToolbar.kt](../app/src/main/java/eu/kanade/presentation/manga/components/MangaToolbar.kt)
- [app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsSecurityScreen.kt](../app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsSecurityScreen.kt)
- [app/src/main/java/eu/kanade/tachiyomi/App.kt](../app/src/main/java/eu/kanade/tachiyomi/App.kt)
- [app/src/main/java/eu/kanade/tachiyomi/data/backup/create/creators/MangaBackupCreator.kt](../app/src/main/java/eu/kanade/tachiyomi/data/backup/create/creators/MangaBackupCreator.kt)
- [app/src/main/java/eu/kanade/tachiyomi/data/backup/models/BackupManga.kt](../app/src/main/java/eu/kanade/tachiyomi/data/backup/models/BackupManga.kt)
- [app/src/main/java/eu/kanade/tachiyomi/data/backup/restore/restorers/MangaRestorer.kt](../app/src/main/java/eu/kanade/tachiyomi/data/backup/restore/restorers/MangaRestorer.kt)
- [app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadNotifier.kt](../app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadNotifier.kt)
- [app/src/main/java/eu/kanade/tachiyomi/data/library/LibraryUpdateNotifier.kt](../app/src/main/java/eu/kanade/tachiyomi/data/library/LibraryUpdateNotifier.kt)
- [app/src/main/java/eu/kanade/tachiyomi/data/notification/NotificationPrivacy.kt](../app/src/main/java/eu/kanade/tachiyomi/data/notification/NotificationPrivacy.kt)
- [app/src/main/java/eu/kanade/tachiyomi/data/notification/NotificationPrivacyPolicy.kt](../app/src/main/java/eu/kanade/tachiyomi/data/notification/NotificationPrivacyPolicy.kt)
- [app/src/main/java/eu/kanade/tachiyomi/ui/base/delegate/SecureActivityDelegate.kt](../app/src/main/java/eu/kanade/tachiyomi/ui/base/delegate/SecureActivityDelegate.kt)
- [app/src/main/java/eu/kanade/tachiyomi/ui/browse/migration/manga/MigrateMangaViewModel.kt](../app/src/main/java/eu/kanade/tachiyomi/ui/browse/migration/manga/MigrateMangaViewModel.kt)
- [app/src/main/java/eu/kanade/tachiyomi/ui/download/DownloadQueueViewModel.kt](../app/src/main/java/eu/kanade/tachiyomi/ui/download/DownloadQueueViewModel.kt)
- [app/src/main/java/eu/kanade/tachiyomi/ui/download/PrivateDownloadOrder.kt](../app/src/main/java/eu/kanade/tachiyomi/ui/download/PrivateDownloadOrder.kt)
- [app/src/main/java/eu/kanade/tachiyomi/ui/history/HistoryViewModel.kt](../app/src/main/java/eu/kanade/tachiyomi/ui/history/HistoryViewModel.kt)
- [app/src/main/java/eu/kanade/tachiyomi/ui/home/PindoramaHomeViewModel.kt](../app/src/main/java/eu/kanade/tachiyomi/ui/home/PindoramaHomeViewModel.kt)
- [app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryTab.kt](../app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryTab.kt)
- [app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryViewModel.kt](../app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryViewModel.kt)
- [app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreen.kt](../app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreen.kt)
- [app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaViewModel.kt](../app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaViewModel.kt)
- [app/src/main/java/eu/kanade/tachiyomi/ui/more/MoreTab.kt](../app/src/main/java/eu/kanade/tachiyomi/ui/more/MoreTab.kt)
- [app/src/main/java/eu/kanade/tachiyomi/ui/security/AuthenticationTarget.kt](../app/src/main/java/eu/kanade/tachiyomi/ui/security/AuthenticationTarget.kt)
- [app/src/main/java/eu/kanade/tachiyomi/ui/security/PrivacySessionManager.kt](../app/src/main/java/eu/kanade/tachiyomi/ui/security/PrivacySessionManager.kt)
- [app/src/main/java/eu/kanade/tachiyomi/ui/security/PrivateContentGate.kt](../app/src/main/java/eu/kanade/tachiyomi/ui/security/PrivateContentGate.kt)
- [app/src/main/java/eu/kanade/tachiyomi/ui/security/PrivateContentSessionManager.kt](../app/src/main/java/eu/kanade/tachiyomi/ui/security/PrivateContentSessionManager.kt)
- [app/src/main/java/eu/kanade/tachiyomi/ui/security/PrivateContentVisibility.kt](../app/src/main/java/eu/kanade/tachiyomi/ui/security/PrivateContentVisibility.kt)
- [app/src/main/java/eu/kanade/tachiyomi/ui/security/UnlockActivity.kt](../app/src/main/java/eu/kanade/tachiyomi/ui/security/UnlockActivity.kt)
- [app/src/main/java/eu/kanade/tachiyomi/ui/stats/StatsViewModel.kt](../app/src/main/java/eu/kanade/tachiyomi/ui/stats/StatsViewModel.kt)
- [app/src/main/java/eu/kanade/tachiyomi/ui/updates/UpdatesViewModel.kt](../app/src/main/java/eu/kanade/tachiyomi/ui/updates/UpdatesViewModel.kt)
- [app/src/main/java/mihon/app/di/AppGraph.kt](../app/src/main/java/mihon/app/di/AppGraph.kt)
- [app/src/main/java/mihon/domain/migration/usecases/MigrateMangaUseCase.kt](../app/src/main/java/mihon/domain/migration/usecases/MigrateMangaUseCase.kt)
- [app/src/main/java/mihon/feature/upcoming/UpcomingViewModel.kt](../app/src/main/java/mihon/feature/upcoming/UpcomingViewModel.kt)
- [app/src/main/res/drawable/ic_private_lock.xml](../app/src/main/res/drawable/ic_private_lock.xml)
- [app/src/test/java/eu/kanade/tachiyomi/data/backup/PrivateMangaBackupTest.kt](../app/src/test/java/eu/kanade/tachiyomi/data/backup/PrivateMangaBackupTest.kt)
- [app/src/test/java/eu/kanade/tachiyomi/data/notification/NotificationPrivacyPolicyTest.kt](../app/src/test/java/eu/kanade/tachiyomi/data/notification/NotificationPrivacyPolicyTest.kt)
- [app/src/test/java/eu/kanade/tachiyomi/ui/download/PrivateDownloadOrderTest.kt](../app/src/test/java/eu/kanade/tachiyomi/ui/download/PrivateDownloadOrderTest.kt)
- [app/src/test/java/eu/kanade/tachiyomi/ui/home/PindoramaHomeViewModelTest.kt](../app/src/test/java/eu/kanade/tachiyomi/ui/home/PindoramaHomeViewModelTest.kt)
- [core/common/src/main/kotlin/eu/kanade/tachiyomi/core/security/PrivateContentSession.kt](../core/common/src/main/kotlin/eu/kanade/tachiyomi/core/security/PrivateContentSession.kt)
- [core/common/src/test/java/eu/kanade/tachiyomi/core/security/PrivateContentSessionTest.kt](../core/common/src/test/java/eu/kanade/tachiyomi/core/security/PrivateContentSessionTest.kt)
- [data/src/main/java/tachiyomi/data/history/HistoryRepositoryImpl.kt](../data/src/main/java/tachiyomi/data/history/HistoryRepositoryImpl.kt)
- [data/src/main/java/tachiyomi/data/manga/MangaMapper.kt](../data/src/main/java/tachiyomi/data/manga/MangaMapper.kt)
- [data/src/main/java/tachiyomi/data/manga/MangaRepositoryImpl.kt](../data/src/main/java/tachiyomi/data/manga/MangaRepositoryImpl.kt)
- [data/src/main/sqldelight/tachiyomi/data/history.sq](../data/src/main/sqldelight/tachiyomi/data/history.sq)
- [data/src/main/sqldelight/tachiyomi/data/mangas.sq](../data/src/main/sqldelight/tachiyomi/data/mangas.sq)
- [data/src/main/sqldelight/tachiyomi/migrations/15.sqm](../data/src/main/sqldelight/tachiyomi/migrations/15.sqm)
- [docs/PRIVATE_LIBRARY_0.3.md](../docs/PRIVATE_LIBRARY_0.3.md)
- [domain/src/main/java/tachiyomi/domain/history/interactor/GetTotalReadDuration.kt](../domain/src/main/java/tachiyomi/domain/history/interactor/GetTotalReadDuration.kt)
- [domain/src/main/java/tachiyomi/domain/history/repository/HistoryRepository.kt](../domain/src/main/java/tachiyomi/domain/history/repository/HistoryRepository.kt)
- [domain/src/main/java/tachiyomi/domain/manga/model/Manga.kt](../domain/src/main/java/tachiyomi/domain/manga/model/Manga.kt)
- [domain/src/main/java/tachiyomi/domain/manga/model/MangaUpdate.kt](../domain/src/main/java/tachiyomi/domain/manga/model/MangaUpdate.kt)
- [domain/src/main/java/tachiyomi/domain/manga/repository/MangaRepository.kt](../domain/src/main/java/tachiyomi/domain/manga/repository/MangaRepository.kt)
- [domain/src/main/java/tachiyomi/domain/manga/service/MangaVisibilityPolicy.kt](../domain/src/main/java/tachiyomi/domain/manga/service/MangaVisibilityPolicy.kt)
- [domain/src/test/java/tachiyomi/domain/manga/service/MangaVisibilityPolicyTest.kt](../domain/src/test/java/tachiyomi/domain/manga/service/MangaVisibilityPolicyTest.kt)
- [i18n/src/commonMain/moko-resources/base/strings.xml](../i18n/src/commonMain/moko-resources/base/strings.xml)
- [i18n/src/commonMain/moko-resources/pt-rBR/strings.xml](../i18n/src/commonMain/moko-resources/pt-rBR/strings.xml)

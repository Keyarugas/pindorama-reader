# Sessão privada e proteção de tela — 0.3

## Auditoria da base

Base: `d34ccc8f4`, branch `pindorama/main` sincronizada com `origin/main`.

- `SecureActivityDelegate` mantinha `requireUnlock` em memória, inicialmente true.
  MainActivity, ReaderActivity e WebViewActivity registram o delegate.
- `UnlockActivity` usava o helper `AuthenticatorUtil`, baseado no AndroidX
  BiometricPrompt com biometria classe 2 ou credencial do Android. Não há PIN próprio.
- `SecurityPreferences`: `use_biometric_lock` (false), `lock_app_after` (0),
  `secure_screen_v2` (INCOGNITO) e o horário persistido `last_app_closed`.
- Timeout: imediato, 1/2/5/10 minutos e Nunca (-1). `App` encaminhava START/STOP
  do ProcessLifecycleOwner. STOP é atrasado pelo AndroidX para tolerar rotação.
- Não havia receiver de screen off. A recriação do processo exigia autenticação,
  mas a indisponibilidade da autenticação desativava automaticamente o bloqueio.
- `WindowExtensions.setSecureScreen` já aplicava FLAG_SECURE. A configuração
  herdada permite Sempre, Modo anônimo e Nunca, independente do app lock.
- Main tratava intents iniciais e novos antes de autenticar. DeepLinkActivity
  encaminha pesquisa, ACTION_SEND e configurações para Main. Atalhos, atualizações
  e downloads abrem Main; notificações de capítulos também abrem Reader diretamente.
- Outros PendingIntents abrem galeria, visualizador de logs, compartilhamento de
  backup, instalador ou navegador externos. Broadcasts executam ações como cancelar,
  baixar ou marcar como lido. OAuth passa por TrackLoginActivity.

## Integração

`PrivacySession` é uma máquina de estados testável sem Activity, com StateFlow:

- LOCKED: conteúdo indisponível; pode iniciar uma tentativa de autenticação.
- AUTHENTICATING: prompt em andamento; conteúdo continua indisponível.
- UNLOCKED: somente com bloqueio desativado ou sucesso válido da tentativa atual.

Um contador de tentativas rejeita callbacks obsoletos. Nenhum estado de autorização
é gravado em disco. Processo novo começa LOCKED se o bloqueio estiver configurado,
mesmo com timeout Nunca. O horário persistido herdado é removido, não reutilizado.

`PrivacySessionManager` mantém uma instância por processo, observa preferências,
recebe screen off e integra o lifecycle. Usa elapsedRealtime (inclui suspensão do
aparelho). O timeout começa no STOP do processo: 0 bloqueia nesse evento; valores
positivos agendam bloqueio e são conferidos novamente ao retornar; -1 não expira.
Atraso do ProcessLifecycleOwner é herdado: não se promete bloqueio em milissegundos
após sair. Uma rotação não deve ser interpretada como saída do aplicativo.

O prompt de credencial do Android pode abrir uma Activity do sistema. Enquanto a
sessão estiver AUTHENTICATING e o helper ativo, essa transição não inicia timeout: cancelar
ou falhar mantém a proteção, e sucesso só é aceito com Activity ao menos STARTED.
Tela apagada invalida uma tentativa ativa, inclusive callbacks posteriores.
Autenticações para alterar preferências não suspendem o timeout de uma sessão já
aberta; ao retornar da credencial do sistema pode ser necessário desbloquear o app.
O receiver bloqueia uma sessão já aberta apenas se `lock_on_screen_off` estiver
habilitado (padrão false). Timeout continua valendo independentemente dessa opção.

`UnlockActivity` reutiliza o helper de autenticação existente. O handle AndroidX é
mantido em ViewModel durante rotação; erro/cancelamento deixa uma tela neutra com
nova tentativa explícita. Indisponibilidade não desativa a preferência. Voltar sai
da tarefa sem autorizar a sessão. Não há PIN paralelo ou recuperação alternativa.

## Tela e Recent Apps

A preferência herdada ganha o título **Proteger conteúdo na tela**. Chave, valores
e padrão INCOGNITO são preservados. Não depende da opção de bloqueio do aplicativo.

O delegate aplica FLAG_SECURE para a preferência selecionada e enquanto a sessão
não estiver UNLOCKED. Oculta a raiz de conteúdo também de toque e acessibilidade,
antes do desenho; o fundo da janela permanece neutro. Com app lock ativo, oculta
conteúdo e ativa proteção ao pausar, antes da captura de Recent Apps. No Android
13+, desativa snapshots de Recent Apps enquanto app lock estiver configurado,
inclusive durante o período de tolerância, para não conservar um preview antigo.
Não altera código do Reader ou da WebView: a proteção chega pelo delegate existente.

Referências Android:
- https://developer.android.com/security/fraud-prevention/activities
- https://developer.android.com/reference/android/app/Activity#setRecentsScreenshotEnabled(boolean)
- https://developer.android.com/reference/androidx/lifecycle/ProcessLifecycleOwner

FLAG_SECURE é uma defesa do sistema contra captura e displays não seguros, não
criptografia. Não impede câmera externa, root, malware privilegiado ou OS alterado;
implementações de fabricantes podem divergir.

## Entradas e limites do escopo

A Main mantém uma fila de intents em SavedStateHandle, incluindo os extras/URI
originais. O consumidor aguarda UNLOCKED antes de navegar ou dispensar a notificação.
Rotação conserva a fila; recriação do processo restaura destinos, mas nunca restaura
a autorização. AssistContent da Main não expõe URL quando a sessão está bloqueada.

O Reader conserva seu intent e seu comportamento herdados; o delegate impede a
exposição de sua janela até autenticar. Isso não impede carregamento interno ou
outras operações do Reader em background. Não foi introduzida biblioteca privada.

Não se alteraram notificações, broadcasts de tarefas, backup ou fluxos externos:
compartilhar backup/imagem e abrir logs/galeria via PendingIntent externo continuam
fora deste portão. OAuth continua seu processamento herdado; ao chegar à Main, a
exibição fica bloqueada. Esta etapa não promete autenticação de todas as operações
em background nem revogação de permissões de URI já concedidas. Uma política futura
para ações externas exigirá mudanças explícitas nesses produtores/receptores.

## Validação manual necessária

- Ativar/desativar app lock com biometria e credencial Android; testar indisponibilidade,
  dedo não reconhecido, cancelamento e retry; confirmar ausência de loops.
- Rotacionar durante prompt e durante tela neutra, inclusive após uma nova tentativa.
- Home/background curto e longo, imediato e Nunca; apagar tela com opção ligada/desligada.
- Autenticação via PIN do sistema, especialmente Android antigo que abre outra Activity.
- Sair para Home durante autenticação e retornar; sucesso atrasado não deve revelar tela.
- Matar processo em background e reabrir por notificação de obra/capítulo, atalhos,
  pesquisa compartilhada ou deep link; conferir autenticação e destino original.
- Recent Apps antes/depois de expirar timeout, screenshot e gravação nos três modos
  herdados; repetir na Main e no Reader, em tema claro/escuro e com TalkBack.
- Cancelar autenticação iniciada por notificação e depois tentar novamente.

# Recuperação de restauração protegida — 0.4.4.1

Base preservada: `850dcfa318377435e1b0aa5f0c75b3883a039b75`
(`feat: integrate protected backup restoration`), após repetir spotlessCheck,
testDebugUnitTest (233 testes) e assembleDebug com JDK 21. Branch de trabalho:
`pindorama/backup-restore-recovery-0.4`. Sem merge ou push.

Este documento atualiza as garantias da etapa 0.4.4; não muda formato, algoritmos,
parâmetros, limites, modelos Protobuf ou restauração convencional.

## Investigação e transação implementada

O caminho estrito de `MangaRestorer` acessa o mesmo Database/SqlDriver injetado em
CategoriesRestorer e nos repositórios de obras, capítulos, categorias e rastreamento.
Consultas de histórico e scanlators também usam esse Database. Não há rede, gravação
de preferências, arquivos de downloads ou reconstrução de cache nesse caminho.
O recálculo legado de intervalo não é executado pelo protegido. A variante de categorias
`restoreData` não modifica a preferência de exibição categorizada.

`ProtectedBackupRestorer` agora abre uma única transação externa envolvendo categorias
e todas as obras selecionadas, depois da autenticação/validação e antes das preferências.
As transações internas existentes participam dela. Não se adicionam launches, novas
conexões ou troca explícita de dispatcher dentro desse bloco. A verificação final de
cancelamento ocorre antes de sair do bloco, além das verificações entre obras.

Foi inspecionado o bytecode do artefato realmente utilizado:
`sqldelight-androidx-driver-android:0637a964a1`. Ele implementa TransactionDispatcher,
possui TransactionElement e distingue enclosingTransaction. O endTransaction emite
COMMIT/ROLLBACK somente na transação externa e libera a conexão de escrita em
NonCancellable. Essa inspeção **não substitui um teste do driver no Android**.

Os testes executam o schema, queries, repositórios e restauradores reais com SQLite JDBC:
faltas após 1, 3 e 10 novas obras, atualização de obra existente, falha SQL em capítulos,
falha SQL na segunda categoria e cancelamento depois de várias operações aninhadas.
Comparam o estado anterior, incluindo a categoria padrão do schema, obras, classificação
privada, capítulos e histórico. Inserções aninhadas não ficam confirmadas após rollback
externo. A biblioteca anterior permanece no cenário de falha demonstrado.

A autenticação e o preparo continuam fora da transação. Não se mantém uma conexão de
escrita durante Argon2, leitura SAF ou interação com o usuário. Não se cria snapshot
manual da biblioteca, staging Protobuf ou arquivo temporário descriptografado. O SQLite
pode usar seu journal/WAL normal no diretório privado do banco, como em outras escritas;
esta etapa não adiciona criptografia ao banco ou ao journal do SQLite.

## Preferências: compensação limitada, não transação distribuída

Somente as nove preferências Boolean permitidas continuam elegíveis. Depois do COMMIT
do banco, o aplicador registra em memória seus valores anteriores e distingue valor
existente de chave ausente. Se uma escrita ou cancelamento falhar, tenta compensar as
chaves já tentadas em ordem inversa, em NonCancellable. Inclui a escrita que modificou
o valor antes de lançar exceção. Uma chave antes ausente é removida; um valor anterior
é restaurado. Não se lê/grava senha ou configuração local de autenticação nesse registro.

Se o valor atual já difere do importado, a compensação o preserva para evitar sobrescrever
uma alteração local observável. Isso não é compare-and-set atômico: concorrência não
coordenada ainda pode correr entre leitura e escrita, ou produzir novamente o mesmo valor.
A compensação não é persistida, não sobrevive à morte do processo e SharedPreferences.apply
não confirma gravação síncrona durável. Não há garantia contra falha de I/O na própria
compensação. Nesse caso é retornado `PREFERENCE_RECOVERY_FAILED`, sem mensagem original,
causa ou dados privados, e a tela orienta revisar as preferências visuais.

A biblioteca já confirmada não é desfeita se a fase posterior de preferências falhar.
Também existe uma janela após COMMIT e antes de registrar sucesso na tela. A mensagem de
interrupção descreve essa possibilidade; uma exceção nunca é anunciada como sucesso integral.
Não se tenta manter uma transação SQLite aberta enquanto grava SharedPreferences para
simular uma atomicidade inexistente entre recursos.

## Workers e concorrência: limite explícito

Nenhuma configuração de tarefas é importada ou reagendada. Segurança, privacidade,
credenciais e sessões continuam locais. Nenhum worker foi cancelado ou desativado.

A transação serializa escritas concorrentes de SQLite conforme o driver e o bloqueio de
escrita. Pode aumentar a espera de outros escritores durante a importação. Isso não é
suspensão lógica de Workers: leituras, rede e tarefas já iniciadas podem continuar.
Um worker pode ler antes da importação e escrever uma resposta antiga depois do COMMIT.
Também não há bloqueio geral de ações da interface que escrevam preferências.

Não foi implementado um mutex apenas no restaurador como se coordenasse o aplicativo
inteiro. Nenhuma garantia de exclusão mútua com todos os Workers é anunciada.

## Proposta que exige aprovação antes de implementar

A recuperação coordenada entre banco, preferências e Workers é uma etapa arquitetural
adicional, não incluída neste patch:

1. Um coordenador compartilhado por todos os escritores relevantes (restauração, workers,
   UI e sincronização), com admissão e drenagem de operações existentes. Nunca aguardar
   rede ou outro worker segurando a conexão de escrita do SQLite.
2. Revalidação de operações que começaram antes da restauração, usando uma geração de
   dados, para impedir aplicação posterior de resultados obsoletos. Um mutex de escrita
   isolado não resolve essa situação.
3. Registro durável de fases e valores antigos/novos **somente** das preferências permitidas,
   sem biblioteca ou credenciais. Um marcador da fase de banco precisaria participar do
   mesmo commit. Isso pode exigir migration pequena e uma política de recuperação inicial.
4. Recuperação idempotente no início do app, antes de liberar os participantes: se o banco
   confirmou, concluir/reconciliar preferências; se não confirmou, descartar a intenção.
   Essa alternativa oferece recuperação para um estado consistente, não rollback mágico
   de um banco já confirmado. Rollback após COMMIT exigiria outra estratégia de undo/staging.
5. Integração explícita com WorkManager, retomada idempotente e testes de falha em cada
   fronteira. Não cancelar indiscriminadamente trabalho do usuário nem alterar intervalos.

Riscos a resolver: deadlock por ordem de locks, workers não cooperantes, morte do processo
com recuperação pendente, replay duplicado, concorrência entre processos, falha persistente
de preferências, migração do registro e custo de transações longas. A decisão de produto é
entre finalizar a importação confirmada por recuperação ou exigir undo completo também
após COMMIT. Não implementei esse coordenador/journal sem aprovação.

## Testes e significado dos resultados

- SQLite real em memória: rollback abrangente após falhas SQL e cancelamento; confirmação
  normal, importação independente e privacidade continuam cobertos.
- SQLite real em disco/subprocesso JVM: schema gerado, estado anterior confirmado,
  transação externa com inserção aninhada e `Runtime.halt(23)` antes do COMMIT. O processo
  termina sem finally/close; a reabertura verifica ausência das alterações não confirmadas
  e preservação do estado anterior. O arquivo é exclusivamente sintético em TempDir JUnit.
- Preferências: AndroidPreferenceStore real sobre SharedPreferences simulado, incluindo
  restauração de valor anterior, remoção de chave antes ausente, cancelamento e falha na
  compensação. Esses testes não demonstram durabilidade do SharedPreferences Android.
- Testes de controle de fluxo com restauradores/transação simulados não demonstram rollback;
  seus resultados são separados dos testes SQLite reais.
- Fixtures criptográficos congelados continuam inalterados. Senha incorreta, adulteração,
  limites e cancelamento anterior à aplicação continuam impedindo entrada no aplicador.

O dispositivo foi detectado via ADB. Nenhum aplicativo foi instalado/aberto, configuração
foi alterada ou biblioteca pessoal foi acessada. **Não houve execução desses testes no
Android**. Permanecem pendentes o driver Android com suspensões, concorrência real de
Workers, encerramento do app e API 26/28. A sonda de subprocesso JVM não é prova desses casos.

## Recursos e distribuição

Limites de arquivo e orçamento de memória não mudaram. A transação não copia o grafo
inteiro novamente, mas prolonga a retenção da conexão de escrita e do journal/WAL. Bancos
grandes podem exigir espaço adicional de armazenamento e fazer outros escritores esperar.
Disco cheio, falha de COMMIT/rollback, perda de energia e corrupção física não foram simulados
no dispositivo. Não há garantia universal de duração, uso de WAL ou sobrevivência do processo.

A medição sintética existente é repetida com a nova transação (100/1.000/5.000 obras e
20 capítulos por obra); não representa o consumo do app Android completo. A preparação
continua em memória e arquivos grandes podem ser recusados sem reduzir a segurança.

Não está aprovado para distribuição pública. Ainda são necessários testes Android com
heap reduzido, concorrência, cancelamento, falhas de armazenamento e recuperação após morte
do processo, além da decisão sobre a arquitetura coordenada descrita acima.

## Arquivos modificados

- `ProtectedBackupRestorer.kt`: transação externa do banco.
- `ProtectedRestorePreferences.kt`: compensação de preferências visuais.
- `BackupError.kt`: código fixo para falha de compensação.
- `ProtectedBackupRestoreTest.kt`: fronteiras de controle de fluxo e compensação simulada.
- `ProtectedRestoreDatabaseTest.kt`: rollback SQLite, encerramento abrupto e medições.
- Strings base e pt-rBR: mensagens genéricas de recuperação/interrupção.
- Este documento. Nenhuma alteração em Workers, banco/schema, criptografia ou Protobuf.

## Validação final desta etapa

JDK 21 local: `spotlessCheck`, `testDebugUnitTest` e `:app:assembleDebug` aprovados.
239 testes, sem falhas, erros ou ignorados: seis testes novos e regressões existentes
adaptadas à fronteira transacional. `git diff --check` também passou.

Na execução completa final, a amostra de 5.000 obras/100.000 capítulos teve preparação de
300 ms e aplicação de 5.737 ms; heap inicial ~86,6 MiB, pico amostrado ~233,1 MiB e incremento
~146,5 MiB. É uma amostra JVM/JDBC, com limitações de amostragem, JIT e ordem da suíte;
não é medição Android nem estimativa do espaço de WAL em bibliotecas reais.

APK: `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`.
As alterações da recuperação permanecem sem commit na branch experimental.

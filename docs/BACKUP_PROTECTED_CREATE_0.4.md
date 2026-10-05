# Criação manual protegida — Pindorama 0.4.3 experimental

Esta etapa integra apenas criação manual. A restauração de `.pindobk` continua ausente
da interface e dos jobs reais. Não há backup automático criptografado, Keystore,
armazenamento permanente de senhas, migração de arquivos ou novo formato criptográfico.
Não está pronta para distribuição pública. A tela informa essas limitações e orienta
a manter outras cópias válidas; não apresenta o arquivo experimental como cópia única.

## Preparação e arquitetura

Branch criada de `pindorama/main` limpa, sincronizada com `origin/main` em `3c2ba4ecc`.
Foram lidos os documentos do motor 0.4.2, sua revisão e os guardrails 0.4.1.

`BackupCreator` compartilhava montagem de modelos, serialização e gravação GZIP direta
no destino. A montagem foi extraída para um método privado comum. A entrada convencional
continua verificando `ConventionalBackupPolicy` sobre o snapshot efetivo, antes de converter
obras ou abrir saída. O caminho protegido é um método manual específico; uma instância
automática o rejeita. Nenhuma preferência, opção de UI ou estado de sessão desliga a
proteção do caminho convencional. Modelos Protobuf e `Manga.isPrivate` não foram alterados.

Fluxo protegido:

1. Confirmar senha e verificar disponibilidade mínima de recursos antes do seletor.
2. Capturar opções e senha somente em memória; o seletor cria um documento `.pindobk`
   com nome sugerido `Pindorama_<UUID>.pindobk`, sem título, contagem ou sinalizador privado.
3. Reavaliar recursos e obter o snapshot da biblioteca, incluindo obras privadas
   selecionadas e não favoritas lidas quando essa opção estiver ativa.
4. Serializar os mesmos modelos Protobuf; comprimir com GZIP em memória, sob os limites
   existentes. Limpar os arrays próprios de Protobuf/compressão quando não necessários.
5. Usar exclusivamente `BackupCrypto` 0.4.2 para criptografar esse payload opaco. O envelope,
   AAD, AES-256-GCM, Argon2id 64 MiB/t=3/p=4, salt, nonce e tag permanecem inalterados.
6. Somente depois de criptografar, abrir o destino vazio e escrever ciphertext em blocos.
7. Fechar a saída, reabrir para leitura e comparar todos os bytes com o envelope original,
   exigindo também EOF exato. Só então mostrar conclusão. Não se descriptografa o destino
   para validar a escrita, evitando nova KDF/cópia de plaintext. A comparação preserva
   os bytes autenticados produzidos pelo motor, inclusive cabeçalho e tag.

`ProtectedBackupPayload` concentra compressão limitada, pré-checagem, integração com o
motor e classificação de falhas. `ProtectedBackupPublication` concentra publicação e
releitura. `publishBackup` é reutilizado com retenção vazia: criação manual nunca lista
ou remove os outros backups. O caminho convencional e o job automático mantêm a política
anterior. `.tachibk` não é alterado; GZIP da JDK é uma codificação convencional compatível.

## Interface, senha e ciclo de vida

Na tela existente, a opção protegida é padrão. Desmarcá-la é uma escolha explícita do
formato convencional, com o aviso de ausência de criptografia. Não existe fallback após
erro protegido. A confirmação da senha é exata, sem trim/normalização; Unicode é preservado.
Aplicam-se os limites de UTF-16/UTF-8 do motor; a UI não trunca silenciosamente senhas.

Os textos dos campos usam `remember`, nunca `rememberSaveable`. A senha preparada fica
em `CharArray` privado no ViewModel durante o seletor e a operação. Não aparece em State,
SavedStateHandle, WorkData, preferências, notificações ou logs. Os campos são esvaziados
ao iniciar o seletor; cancelar o seletor descarta o array. Perda do processo não permite
retomar automaticamente: o usuário precisa fornecer a senha novamente. Arrays próprios
são limpos na conclusão/cancelamento, inclusive se o corpo da coroutine não chegar a iniciar.

O protegido executa em `viewModelScope`/Dispatchers.IO, não no WorkManager. O convencional
continua no job existente, sem senha. Progresso, resultado, erros genéricos e cancelar
ficam na própria tela; não há novas notificações nem compartilhamento automático.
Sair da composição/tela cancela a operação e descarta credenciais preparadas. Mudanças
de configuração podem cancelar a operação: a experiência experimental é deliberadamente
conservadora e não oferece continuação em segundo plano com senha persistida.

`runInterruptible` liga cancelamento da coroutine à interrupção reconhecida pelo motor.
Há checagens de cancelamento durante compressão, escrita, releitura e antes da conclusão.
Argon2/JCA e um provedor bloqueado podem levar tempo até retornar; não se promete cancelamento
instantâneo. Morte do processo não executa finally/cleanup. A corrida após a última checagem
não pode ser eliminada, e um arquivo completamente gravado pode existir sem confirmação UI.

Strings/estado interno do Compose, encoder, serializer e Deflater podem manter cópias
fora do controle do aplicativo. Zeroização absoluta na JVM/ART não é prometida. O app
não altera modelos reais para apagá-los: somente buffers próprios são limpos.

## Memória e Android

Não houve aumento de limites: Protobuf até **64 MiB**, GZIP até **128 MiB**, envelope até
**128 MiB + 80 bytes**. Não há descompressão nem restauração real nesse fluxo.

Admissão mínima de 144 MiB adicionais livres é checada antes do seletor, antes de ler os
modelos e antes de serializá-los. Após comprimir, o motor aplica o orçamento proporcional
original (`4*N + 2*memoriaArgon + 16 MiB`). Um heap máximo de 128 MiB não comporta esse
perfil; é recusado sem reduzir a segurança. A verificação antes do seletor é apenas uma
antecipação: a checagem de execução continua obrigatória.

O serializer Protobuf atual retorna um ByteArray completo; não fornece neste caminho
escrita incremental limitada. Seu tamanho só é conhecido depois da serialização. O grafo
de capítulos/preferências também pode ser grande antes disso. O orçamento é conservador,
não reserva heap nem prevê exatamente todas as alocações; OOM recuperável é convertido
em erro fixo, mas não há garantia de sobrevivência à pressão extrema ou encerramento pelo SO.
Buffers aguardando GC podem causar recusas conservadoras. Não se força GC em produção.

Durante serialização coexistem modelos, buffers internos e o array final; durante compressão,
Protobuf e buffers GZIP. O array Protobuf é limpo antes da criptografia, e o GZIP após ela.
O coletor pode ainda não ter recuperado buffers antigos. O motor mantém suas cópias da versão
0.4.2; a verificação do destino usa somente 8 KiB adicionais e o ciphertext já produzido.
Nenhum desses buffers de plaintext é escrito em disco, cache ou armazenamento compartilhado.

As APIs novas de GZIP, streams e coroutines são compatíveis com o mínimo API 26 do projeto;
a compilação/dex do APK continua com esse mínimo. Execução física em API 26/28, Conscrypt
do caminho antigo e o fluxo completo dentro do app em aparelhos de heap reduzido continuam
pendentes. Os resultados Android da revisão 0.4.2 mediam somente o motor isolado e não
foram usados como prova de consumo desta integração. Nenhuma biblioteca pessoal foi acessada.

### Medição JVM da criação completa, com dados sintéticos

`BackupCreatorGuardrailsTest` mede o próprio `backupProtected`, com consultas substituídas
por geradores sintéticos e UniFile simulado em memória. Inclui montagem de modelos,
Protobuf, GZIP, Argon2/AES, escrita, fechamento e releitura. Cada obra possui descrição
de 1.024 caracteres pseudoaleatórios sintéticos e 20 capítulos. A semente controla apenas
o conteúdo de teste, nunca salt/nonce. Há GC explícito somente antes da medição de teste.

Uma amostra da execução com JDK 21 e heap 512 MiB:

| Obras / capítulos sintéticos | Arquivo bytes | Tempo total ms | Heap inicial MiB | Pico amostrado MiB | Adicional MiB |
| --- | --- | --- | --- | --- | --- |
| 100 / 2.000 | 68.813 | 515 | 28,1 | 104,8 | 76,8 |
| 1.000 / 20.000 | 686.050 | 382 | 32,6 | 119,8 | 87,2 |
| 5.000 / 100.000 | 3.430.089 | 614 | 40,3 | 175,8 | 135,5 |

Amostragem de heap aproximadamente a cada 1 ms, incluindo objetos aguardando GC; pode
perder picos curtos. Não é RSS nem benchmark estatístico. JIT, ordem dos testes, MockK e
cópias do provedor simulado afetam os números. Consultas reais, cache do app, UI e provedores
SAF não foram medidos aqui. Títulos/contagens impressos no teste são apenas métricas
sintéticas; a implementação de produção não registra essas informações.

## Falhas de armazenamento e preservação

O destino deve ser arquivo, reportar tamanho zero e também estar vazio numa leitura
anterior à escrita. Um documento não vazio, mesmo com tamanho incorretamente reportado,
é recusado sem sobrescrita ou remoção. Provedores que não permitem releitura ou não
informam tamanho confiável são recusados de forma conservadora.

Erros de escrita, fechamento, corrupção, truncamento, bytes extras e cancelamento não
geram sucesso. Cleanup tenta excluir apenas o documento selecionado, previamente vazio;
nenhuma cópia anterior é listada ou podada. Se houver falha de acesso antes de escrever,
o seletor pode deixar um arquivo vazio. Falha de remoção/morte do processo pode deixar
ciphertext parcial. Nunca é adotada uma alternativa convencional automática.

SAF não oferece publicação/renomeação atômica universal. Releitura confirma os bytes
visíveis naquele momento; não garante fsync remoto, durabilidade, ausência de alteração
concorrente por outro agente ou remoção de versões mantidas pelo provedor. O nome pode
ser alterado pelo usuário/provedor; a segurança depende do conteúdo, não da extensão.

Os erros expostos contêm somente códigos fixos. Mensagens, causas e exceções suprimidas
do provedor são descartadas, inclusive quando close acrescenta dados a um erro já genérico.
Não se registram URI, senha, título, categoria ou contagem privada. O erro de memória
orienta sobre recursos; não propõe reduzir Argon2 ou exportar em texto claro.

## Testes e validação manual pendente

Validação final com JDK 21 local: `spotlessCheck`, `testDebugUnitTest` e
`:app:assembleDebug` aprovados. Foram contabilizados 211 testes, sem falhas, erros ou
ignorados (20 novos nesta etapa). APK gerado em
`app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`.

Testes cobrem criação privada/Unicode, campos isPrivate, confirmação incorreta, UI padrão,
descarte de preparação, recusa automática, memória, limites, cancelamento prévio e durante
escrita, erros de write/close/read/cleanup, destinos não vazios, corrupção/truncamento,
diagnósticos e manutenção do comportamento convencional/retenção automática. Cancelar antes
do início da coroutine também deve liberar o estado ocupado sem anunciar sucesso.

O fixture independente 0.4.2 é publicado sem alterações e lido pelo motor. Um novo fixture
`backup-protected-create/private-gzip-v1.pindobk` usa Protobuf manual sintético com campo
1000 (`isPrivate`), GZIP, libargon2 de referência e cryptography/OpenSSL; seu hash é fixado.
Ele é lido e seu payload passa pela nova criação/publicação, preservando a semântica.
Fixtures antigos e formato criptográfico não foram modificados. Nenhum teste habilita
restauração real no aplicativo.

Checklist antes de integrar/distribuir, usando somente biblioteca de teste:

- Verificar opção protegida padrão, aviso experimental e ausência de restauração na UI.
- Confirmar senha Unicode; rejeitar confirmação diferente, vazio e entrada acima do limite.
- Cancelar seletor, navegar para trás, girar tela e encerrar processo; exigir nova senha
  quando perdida e verificar ausência de sucesso indevido.
- Exportar obra privada com sessão privada bloqueada e desbloqueada; verificar isPrivate
  por ferramenta de teste isolada, sem habilitar restauração no app.
- Escolher convencional explicitamente: biblioteca pública exporta `.tachibk`; obra privada
  incluída bloqueia a operação; automático permanece convencional e protegido pela política.
- Testar erro/perda de permissão/armazenamento cheio, provider local e nuvem; preservar
  backups anteriores e inspecionar eventuais documentos vazios/ciphertext parcial.
- Tentar destino não vazio; nunca sobrescrever a cópia anterior. Testar provedor que não
  suporta releitura e confirmar erro conservador.
- Cancelar durante Argon2 e durante escrita/releitura; verificar resposta após I/O desbloquear.
- Medir memória no processo completo em API 26/28 e Android recente, com pouca memória
  disponível e bibliotecas maiores/incompressíveis; conferir erros genéricos sem downgrade.
- Inspecionar logcat/notificações e caches com dados sintéticos: nenhum segredo/caminho,
  título/contagem privada ou arquivo intermediário em texto claro criado por este fluxo.

Nenhum desses testes manuais foi considerado concluído nesta entrega.

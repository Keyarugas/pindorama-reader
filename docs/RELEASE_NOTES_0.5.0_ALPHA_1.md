# Pindorama! 0.5 Alpha 1

Rascunho das notas de release — publicação e validação de upgrade pendentes.
Versão: `0.5.0-alpha.1` · versionCode: `30` · Android 8.0 ou superior.

Esta é a primeira release pública da série 0.5. A última release pública foi a
Pindorama! 0.2 Alpha 1 (`v0.2.0-alpha.1`); os ciclos 0.3 e 0.4 foram milestones
internas de desenvolvimento e não tiveram releases públicas. O salto 0.2 → 0.5
é intencional.

As mudanças abaixo correspondem ao histórico desde `v0.2.0-alpha.1` até o HEAD
base deste rascunho, `21b6e40b5`.

## Destaques

### Privacidade

- Biblioteca Privada, com aba própria para conteúdo desbloqueado e autenticação
  independente do bloqueio geral do aplicativo. Desbloquear o app não libera
  automaticamente as obras privadas.
- Reforços no bloqueio do aplicativo e na proteção de tela.
- Níveis de privacidade para notificações e proteção da apresentação de notificações
  relacionadas a obras privadas.
- Sanitização de dados sensíveis em diagnósticos e redução da exposição de dados
  no logging HTTP.
- Remoção de Firebase Analytics, Crashlytics, `google-services.json`, infraestrutura
  Google Services/Firebase no Gradle e código, preferências e textos relacionados.
  O classpath de runtime atual não inclui Firebase ou Google Play Services.

### Backup e restauração

- Criação e restauração de backups protegidos por senha, disponíveis nas
  configurações. A senha não é persistida pelo aplicativo.
- Restauração protegida testada em dispositivo Android real.
- Interface de restauração com tratamento específico para senha incorreta, arquivo
  inválido, formato incompatível, operação ocupada (BUSY) e cancelamento, com nova
  tentativa quando o estado permite recuperação.
- Exportação convencional com avisos e proteções para dados sensíveis.
- Criação de backup com snapshot lógico coordenado e consistente.
- Restauração com exclusividade e controle de geração para impedir gravações
  concorrentes relevantes e descartar resultados antigos de operações iniciadas
  antes da restauração. A etapa de coordenação 0.4 está encerrada.

### Interface e identidade

- Branding interno próprio do Pindorama no splash, cabeçalhos e telas do aplicativo.
- Novo ícone do launcher, com versões adaptativa, temática/monocromática e legada.
- Refinamentos visuais e de mensagens na restauração protegida.
- Correção do acesso aos detalhes da obra a partir de “Continuar lendo” na Home.
- Identificação da versão prerelease como Alpha; versões finais sem esse marcador
  continuam apresentadas como Stable.

## Atualizando da 0.2 Alpha 1

Esta release mantém o applicationId `app.pindorama.reader`. Recomendamos criar e
guardar um backup antes de atualizar. A compatibilidade de upgrade sobre a release
pública 0.2 Alpha 1 será validada antes da publicação final; esse teste ainda não
é declarado como aprovado neste rascunho.

## Downloads

Os links e tamanhos serão preenchidos após a preparação dos artefatos finais.
Hashes ainda não foram definidos.

- **ARM64** — recomendado para a maioria dos celulares Android modernos.
  Link do APK: pendente.
- **Universal** — compatibilidade mais ampla com as arquiteturas suportadas.
  Link do APK: pendente.

## Avisos

- Esta versão continua Alpha. Bugs e mudanças incompatíveis podem ocorrer;
  mantenha backups antes de atualizar e ao testar novos recursos.
- Guarde a senha do backup protegido: sem ela, o arquivo não pode ser restaurado.
  Backups convencionais podem conter dados sensíveis sem criptografia.
- A Biblioteca Privada e a proteção do backup não criptografam o banco local,
  capas, caches ou downloads. Não há garantia contra sistema comprometido, root,
  todas as falhas de armazenamento ou interrupções do processo.
- O aplicativo não fornece nem hospeda conteúdo. Extensões, fontes e serviços
  externos têm práticas próprias de privacidade e podem tratar dados fora das
  proteções do Pindorama. A remoção de Firebase não é promessa de anonimato,
  ausência de rede ou ausência de telemetria em componentes externos.
- Pindorama! é um fork independente do [Mihon](https://github.com/mihonapp/mihon),
  sem afiliação oficial com sua equipe. Os créditos do trabalho herdado e a licença
  Apache 2.0 são preservados.

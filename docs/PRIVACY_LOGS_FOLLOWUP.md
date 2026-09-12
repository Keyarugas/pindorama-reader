# Diagnóstico: limites e próximos passos

A sanitização desta etapa remove valores de headers HTTP, credenciais nomeadas,
userinfo/query/fragment de URLs e caminhos Android reconhecidos. Preserva classes,
frames, IDs técnicos e scheme/host/path das URLs. Não é detecção universal de segredos:
segredos sem rótulo, títulos livres, caminhos com espaços sem delimitadores e tokens
embutidos em segmentos de URL podem permanecer. Extensões podem escrever diretamente
em loggers externos; a sanitização de exportação não apaga registros já feitos no OS.
Reader, WebView, backup e notificações não foram editados nesta etapa.

O relatório padrão não inclui Installation ID. Mantém versão/build do app, Android,
modelo/fabricante, versão WebView, horário e extensões problemáticas para diagnóstico.
Esses dados ainda podem permitir correlação. O compartilhamento continua explícito.

## Prévia antes de compartilhar

`CrashLogUtil` é um serviço com contexto de aplicação, chamado tanto por Configurações
quanto pela tela de crash. Uma prévia completa requer retornar um resultado preparado
às duas telas, fornecer um visualizador rolável com compartilhar/cancelar e gerenciar
vida útil/limpeza do arquivo. Fazer isso em uma mudança própria, com strings localizadas,
acessibilidade e testes de cancelamento. A tela de crash atual mostra apenas a exceção
sanitizada, não uma prévia integral do arquivo final.

## Validação futura

- Exercitar diagnóstico com extensões reais sem usar credenciais reais nos testes.
- Rever separadamente mensagens livres do Reader, backup e notificações.
- Considerar reduzir detalhes de dispositivo, horários e lista de extensões no relatório.
- Revisar retenção e limpeza de relatórios temporários, inclusive após falhas de exportação.

# Pindorama!

**Mais histórias para um Brasil maior.**

Leitor Android de mangás, manhwas, webtoons e conteúdos compatíveis, com uma interface própria e atenção especial à privacidade. O Pindorama! é um **fork independente do [Mihon](https://github.com/mihonapp/mihon)**: preserva a base do projeto original e desenvolve funcionalidades próprias, sem afiliação ou suporte oficial da equipe Mihon.

**Estado:** desenvolvimento em fase Alpha · **Android 8.0+** · **Licença [Apache 2.0](LICENSE)** · **Gratuito e de código aberto**

> **Atenção:** o código da branch principal pode incluir recursos experimentais ainda ausentes dos APKs publicados. Uma funcionalidade em desenvolvimento não é uma garantia de segurança nem significa que já esteja disponível para uso cotidiano. Guarde uma cópia independente dos seus dados antes de testar versões Alpha.

## O que o Pindorama acrescenta

### Leitura e identidade visual

- Página **Início** com “Continuar lendo”, acesso à obra e retomada do capítulo, atualizações recentes e resumo da atividade de leitura.
- Navegação com Início, Biblioteca, Histórico, Navegar e Mais.
- Identidade visual própria, tema claro/escuro e apresentação editorial na página inicial.
- Leitor, organização da biblioteca, histórico, categorias e demais recursos de leitura herdados do Mihon.

### Privacidade no aplicativo

- **Bloqueio do aplicativo** usando biometria ou credencial do Android, com opções de bloqueio por inatividade, ao apagar a tela e proteção de conteúdo na tela/visão de aplicativos recentes.
- **Biblioteca Privada:** obras marcadas como privadas ficam ocultas em superfícies locais compatíveis até que o usuário solicite e conclua uma autenticação **separada** do desbloqueio global. A seção “Privadas” reúne essas obras sem alterar suas categorias originais.
- **Notificações:** níveis Normal, Discreto e Privado para reduzir a exposição de informações no sistema; notificações de obras privadas recebem tratamento mais restritivo.
- **Diagnósticos:** redução de informações sensíveis em logs HTTP e relatórios nos caminhos adaptados.
- Firebase Analytics/Crashlytics e o atualizador do Mihon foram desabilitados na configuração do Pindorama. **Não há atualização automática própria implementada nesta etapa.**

**Limites importantes:** ocultar uma obra não criptografa o banco de dados, as capas, os downloads ou outros arquivos locais. Extensões, fontes, serviços de rastreamento e o próprio sistema Android podem processar informações fora das proteções da interface. Notificações já visualizadas ou dados previamente compartilhados com outros aplicativos não podem ser recuperados retroativamente. O Pindorama não promete anonimato nem proteção contra aparelho comprometido.

Para detalhes e limites técnicos, consulte [Biblioteca Privada](docs/PRIVATE_LIBRARY_0.3.md), [sessão e proteção de tela](docs/PRIVATE_SESSION_0.3.md) e [notas sobre diagnósticos](docs/PRIVACY_LOGS_FOLLOWUP.md).

### Backups: situação do código e da distribuição

- **Backup convencional:** o formato herdado continua disponível para dados públicos e outros casos permitidos; é comprimido, **não criptografado**. A exportação convencional de obras privadas selecionadas é bloqueada para evitar uma exposição acidental. Mesmo um backup convencional sem obras privadas pode conter dados sensíveis.
- **Motor de criptografia isolado (código da branch principal):** existe uma implementação experimental para um formato protegido, com Argon2id e AES-256-GCM, acompanhada de testes e documentação. **Esse motor isolado, por si só, não oferece ainda um fluxo completo de criação/restauração na interface do aplicativo.**
- A integração da criação e da restauração protegidas, a recuperação de falhas e a coordenação com tarefas automáticas estão em desenvolvimento e exigem validação adicional antes de serem anunciadas como recursos disponíveis em uma distribuição pública.
- A senha de um futuro backup protegido será necessária para recuperá-lo. O bloqueio biométrico do aplicativo não substitui essa senha.

Leia [proteções do backup convencional](docs/backup-guardrails.md), [especificação do motor criptográfico](docs/BACKUP_CRYPTO_0.4.md) e [revisão técnica](docs/BACKUP_CRYPTO_REVIEW_0.4.md). A revisão interna não constitui auditoria independente ou certificação de segurança.

## Recursos herdados e conteúdo externo

O Pindorama preserva recursos da base Mihon, como leitura local, modos configuráveis de leitura, biblioteca, histórico, categorias, downloads e integração com serviços de acompanhamento compatíveis.

**O aplicativo não fornece nem hospeda obras.** Fontes e extensões são mantidas por terceiros e podem estabelecer conexões próprias. A compatibilidade com o ecossistema herdado não garante que uma extensão específica funcione ou seja confiável. Cabe ao usuário observar a legislação aplicável e as condições dos serviços acessados.

## Downloads e versões

As versões públicas experimentais são disponibilizadas em [GitHub Releases](https://github.com/Keyarugas/pindorama-reader/releases). Verifique as notas e os arquivos de **cada** versão antes de instalar; recursos presentes na branch principal podem não integrar o APK mais recente.

- [Pindorama! 0.2 Alpha 1](https://github.com/Keyarugas/pindorama-reader/releases/tag/v0.2.0-alpha.1): primeira versão pública com a nova página Início.
- [Pindorama! 0.1 Alpha 3](https://github.com/Keyarugas/pindorama-reader/releases/tag/v0.1.0-alpha.3): versão experimental anterior, com a identidade e o tema próprios.

Prefira baixar APKs exclusivamente das Releases deste repositório e confira as informações publicadas junto ao arquivo. Builds debug da CI são artefatos de teste, não lançamentos estáveis. **Não há ainda um canal de atualização automática do Pindorama.**

## Desenvolvimento e contribuições

Requisitos para compilar localmente: **JDK 21**, Android SDK e ambiente Android/Gradle configurado.

    ./gradlew spotlessCheck
    ./gradlew testDebugUnitTest
    ./gradlew :app:assembleDebug

Para propor mudanças ou relatar problemas, utilize as [Issues](https://github.com/Keyarugas/pindorama-reader/issues) e [Pull Requests](https://github.com/Keyarugas/pindorama-reader/pulls) **deste repositório**. Antes de compartilhar logs ou capturas, remova títulos de obras privadas, URLs com tokens, credenciais e outros dados pessoais. Leia [CONTRIBUTING.md](CONTRIBUTING.md) e o [Código de Conduta](CODE_OF_CONDUCT.md).

O projeto segue a licença [Apache 2.0](LICENSE) e mantém os créditos e avisos de autoria da base herdada. Atualizações do [Mihon original](https://github.com/mihonapp/mihon) são incorporadas após revisão, não automaticamente. Pedidos de suporte relativos às alterações do Pindorama devem ser encaminhados aqui, não à equipe do Mihon.

---

### Credits

Thank you to all the people who have contributed!

<a href="https://github.com/mihonapp/mihon/graphs/contributors">
    <img src="https://contrib.rocks/image?repo=mihonapp/mihon" alt="Mihon app contributors" title="Mihon app contributors" width="800"/>
</a>

### Disclaimer

The developer(s) of this application does not have any affiliation with the content providers available, and this application hosts zero content.

### License

<pre>
Copyright © 2015 Javier Tomás
Copyright © 2024 Mihon Open Source Project

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
</pre>

# ADR-0001: Stack e versões

- **Status:** Proposto
- **Data:** 09/11/2026
- **Tarefas que implementam:** 4 (monorepo, Node, TypeScript, ESLint), 5 (PostgreSQL no Docker), 6 (backend), 7 (CI e Dependabot), 13 (clientes gerados), 15 e 16 (aplicativos Electron)

## Contexto

A especificação (§5) escolhe as tecnologias, mas deixa a versão exata do Spring Boot para este ADR ("4.1.x, versão exata fixada no ADR-0001") e não fixa nenhuma outra. Versão errada no começo custa caro: o Flyway, o PostgreSQL e o Electron têm ciclos de suporte curtos, e algumas bibliotecas de front-end ainda não aceitam a versão mais nova do TypeScript.

Todas as versões abaixo foram **consultadas na fonte oficial em 09/11/2026**. Onde a fonte não publica data de fim de suporte, a tabela diz "sem data publicada" em vez de inventar uma.

## Decisão

### Versões

| Item | Versão | Fonte e data da consulta | Suporte |
| --- | --- | --- | --- |
| JDK (Temurin) | **21.0.13+9** (21 LTS) | [API da Adoptium](https://api.adoptium.net/v3/assets/latest/21/hotspot?image_type=jdk&os=windows&architecture=x64), registro atualizado em 29/10/2026. Consulta em 09/11/2026 | [Adoptium](https://adoptium.net/support/): disponível "pelo menos até dez/2029" |
| Spring Boot | **4.1.2** | [Maven Central](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot/maven-metadata.xml), [spring.io](https://spring.io/projects/spring-boot) e [requisitos do sistema](https://docs.spring.io/spring-boot/system-requirements.html) (Java 17 a 27, Maven 3.6.3 ou mais novo). Consulta em 09/11/2026 | [API de gerações do Spring](https://api.spring.io/projects/spring-boot/generations): linha 4.1 lançada em 30/06/2026; código aberto até 31/07/2027; suporte comercial até 31/07/2028 |
| Maven wrapper | wrapper **3.3.4**, que baixa o Maven **3.9.16** | [Maven Central, wrapper](https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper-distribution/maven-metadata.xml) e [Maven Central, Maven](https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/maven-metadata.xml). Consulta em 09/11/2026. O Maven 4 ainda está em candidata a versão (4.0.0-rc-7) | Sem data publicada |
| Flyway | **12.4.0** (versão gerenciada pelo Boot 4.1.2), mais o módulo `flyway-database-postgresql` | [Pom do Boot 4.1.2](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.2/spring-boot-dependencies-4.1.2.pom), [Maven Central, flyway-core](https://repo.maven.apache.org/maven2/org/flywaydb/flyway-core/maven-metadata.xml) (publicada em 14/04/2026; a mais nova é a 13.12.1) e [bancos suportados](https://documentation.red-gate.com/fd/supported-databases-and-versions-143754067.html) (PostgreSQL 12 a 18). Consulta em 09/11/2026 | Sem data publicada |
| springdoc-openapi | **3.2.0** (`springdoc-openapi-starter-webmvc-ui`) | [Maven Central](https://repo.maven.apache.org/maven2/org/springdoc/springdoc-openapi-starter-webmvc-ui/maven-metadata.xml) (24/10/2026) e [notas de versão](https://github.com/springdoc/springdoc-openapi/releases): construída sobre o Spring Boot 4.1.2. Consulta em 09/11/2026 | Sem data publicada |
| Testcontainers | **2.0.5**, módulo `org.testcontainers:testcontainers-postgresql` | [Maven Central](https://repo.maven.apache.org/maven2/org/testcontainers/testcontainers-postgresql/maven-metadata.xml), [documentação](https://java.testcontainers.org/modules/databases/postgres/) e pom do Boot 4.1.2 (gerencia a 2.0.5). Consulta em 09/11/2026 | Sem data publicada |
| PostgreSQL | **18.6** (imagem `postgres:18.6`) | [Política de versões](https://www.postgresql.org/support/versioning/) e [Docker Hub](https://hub.docker.com/v2/repositories/library/postgres/tags/18.6) (tag de 06/11/2026). Consulta em 09/11/2026 | Lançada em 25/09/2025; fim do suporte em **14/11/2030** |
| Node.js | **26.13.0** "Lithium" (LTS ativo desde 28/10/2026) | [Índice de versões](https://nodejs.org/dist/index.json) e [calendário oficial](https://raw.githubusercontent.com/nodejs/Release/main/schedule.json). Consulta em 09/11/2026 | Manutenção a partir de 20/10/2027; fim em **30/04/2029** |
| npm | **11.20.0** (a que vem junto com o Node 26.13.0) | [Índice de versões do Node](https://nodejs.org/dist/index.json). A 12.2.2 existe no [registro](https://registry.npmjs.org/npm/latest), mas não acompanha o Node. Consulta em 09/11/2026 | Acompanha o Node |
| Electron | **45.2.3** (Chromium M156, Node 24.22.0 embutido) | [Registro do npm](https://registry.npmjs.org/electron/latest) (publicada em 04/11/2026) e [calendário](https://releases.electronjs.org/schedule). Consulta em 09/11/2026 | O major 45 saiu em 20/10/2026 e termina em **27/04/2027** (data provisória). Só os 3 majors mais novos têm suporte |
| electron-vite | **6.0.0** (aceita Vite 6, 7 ou 8), com Vite **8.3.6** | [Registro do npm, electron-vite](https://registry.npmjs.org/electron-vite/latest) (14/10/2026) e [Vite](https://registry.npmjs.org/vite/latest) (05/11/2026). Consulta em 09/11/2026 | Sem data publicada |
| electron-builder e electron-updater | **26.18.0** e **6.8.11** | [Registro do npm, electron-builder](https://registry.npmjs.org/electron-builder/latest) (20/10/2026) e [electron-updater](https://registry.npmjs.org/electron-updater/latest). Consulta em 09/11/2026 | Sem data publicada |
| React | **19.3.1** | [Registro do npm](https://registry.npmjs.org/react/latest) (14/10/2026). Consulta em 09/11/2026 | Sem data publicada |
| TypeScript | **6.0.3** (e não a 7.0.2) | [Registro do npm](https://registry.npmjs.org/typescript). A 7.0.2 é a marcada como `latest`, mas o [typescript-eslint 8.72.1](https://registry.npmjs.org/typescript-eslint/latest) só aceita TypeScript abaixo de 6.1.0. Consulta em 09/11/2026 | Sem data publicada |
| Vitest | **5.1.0** | [Registro do npm](https://registry.npmjs.org/vitest/latest) (02/11/2026): exige Node 22.12 ou mais novo. Consulta em 09/11/2026 | Sem data publicada |
| Gerador de cliente a partir do OpenAPI | **@hey-api/openapi-ts 0.99.0**, versão fixa (sem `^`) | [Registro do npm](https://registry.npmjs.org/@hey-api/openapi-ts/latest): aceita TypeScript 5.5.3 em diante, inclusive o 6. Consulta em 09/11/2026 | Sem data publicada; a biblioteca ainda é 0.x |

Também vêm do Boot 4.1.2 e valem para o backend: Spring Framework 7.0.10 e driver JDBC do PostgreSQL 42.7.14. A tarefa 6 não deve fixá-las à mão, e sim herdar do Boot.

### Por que cada uma

- **Java 21 (Temurin):** é o que a especificação pede (§5), é versão LTS e o Boot 4.1 suporta de 17 a 27. Temurin é a distribuição aberta mantida pela Adoptium, sem custo de licença. É também o JDK que já está instalado na máquina de desenvolvimento.
- **Spring Boot 4.1.2:** é o patch estável mais recente da linha 4.1 que a especificação pede. Traz Flyway, Testcontainers e driver do PostgreSQL já compatíveis entre si.
- **Maven 3.9.16 pelo wrapper:** o wrapper fixa a versão do Maven no repositório, então ninguém depende do Maven instalado na máquina (§5, §14). O Maven 4 ainda não saiu de candidata a versão.
- **Flyway 12.4.0:** é a que o Boot 4.1.2 gerencia e testa; trocar para a 13.x exigiria sobrepor a versão do Boot, e o ganho não compensa o risco logo na fundação. Suporta PostgreSQL 18. O módulo `flyway-database-postgresql` é obrigatório desde o Flyway 10.
- **springdoc 3.2.0:** é a linha 3.x, feita para o Spring Boot 4, e a 3.2.0 foi construída sobre o Boot 4.1.2. É quem gera o OpenAPI (§5) de onde saem os clientes (ADR-0003).
- **Testcontainers 2.0.5:** é a que o Boot 4.1.2 gerencia. Na linha 2 o módulo do PostgreSQL se chama `testcontainers-postgresql` (o antigo `postgresql` parou na 1.21.4). Sobe um PostgreSQL de verdade nos testes (§13.4), o que é indispensável para os testes de concorrência do ADR-0002.
- **PostgreSQL 18.6:** é a versão principal mais nova com suporte (até 11/2030), o Flyway lista o 18 e o Testcontainers sobe qualquer imagem. É um projeto novo, então não há motivo para começar numa versão que perde suporte um ano antes. A 18 também tem as funções de transação usadas no ADR-0002 (`pg_current_xact_id`, `pg_snapshot_xmin`) e o limite `transaction_timeout`. A 19 ainda está em candidata a versão.
- **Node 26.13.0 e npm 11.20.0:** o pedido foi "LTS ativo", e hoje é a linha 26. O npm é o que vem com ele, para ter uma única versão a fixar. O Vitest, o electron-vite e o Electron aceitam o Node 26.
- **Electron 45.2.3:** é o estável mais novo e o que tem o maior prazo de suporte. O Electron embute o próprio Node (24.22.0), diferente do Node 26 de desenvolvimento; os testes que dependem do Node embutido ficam para a tarefa 18.
- **electron-vite 6 e Vite 8:** juntam o processo principal, a ponte e a tela React num só fluxo de desenvolvimento, e atendem os dois aplicativos. O Vite 8 é o que o plugin React atual exige.
- **electron-builder e electron-updater:** é a combinação pedida na §5 para instalador e atualização automática.
- **React 19.3.1 e Vitest 5.1.0:** versões estáveis atuais; o Vitest usa a mesma configuração do Vite.
- **TypeScript 6.0.3:** a 7.0.2 (reescrita nativa) é a mais nova, mas o typescript-eslint, que será o lint dos dois aplicativos, ainda não a aceita. Fica na 6.0.3, a última da linha 6.0.
- **@hey-api/openapi-ts:** aceita TypeScript 6, gera cliente tipado a partir do OpenAPI e permite um cliente por grupo de rotas (ADR-0003). O concorrente mais popular, o openapi-typescript 7.13.0, declara que só funciona com TypeScript 5.

### Como as versões são fixadas

- Backend: `pom.xml` e `.mvn/wrapper/maven-wrapper.properties` (tarefa 6). Imagem do banco com tag completa, `postgres:18.6`, nunca `latest` (tarefa 5).
- Front-end: arquivo de versão do Node na raiz (`.nvmrc`), campo `engines`, `package-lock.json` sempre instalado com `npm ci`. Electron, electron-builder, TypeScript e o gerador de cliente ficam em versão exata, sem `^` (tarefa 4).
- Depois de aceito, a fonte da verdade são esses arquivos. A tabela acima fica como retrato da decisão e não é atualizada a cada patch.

### Regra de atualização

- **Dependabot** abre os Pull Requests de atualização toda semana, agrupados por ecossistema (Maven, npm, GitHub Actions, Docker) (tarefa 7).
- **Patch e minor** entram por Pull Request com o CI verde, sem ADR novo.
- **Troca de versão maior exige ADR novo** que substitui este (por exemplo: Java 21 para 25, Spring Boot 4 para 5, Flyway 12 para 13, PostgreSQL 18 para 19, Node 26 para 28, React 19 para 20, TypeScript 6 para 7). Isso vale também para o gerador de cliente enquanto for 0.x: cada minor dele é tratado como major.
- **Exceção proposta, o Electron:** cada versão maior do Electron tem suporte por cerca de 6 meses e sai a cada cerca de 8 semanas; exigir um ADR a cada uma seria impraticável. Os majors do Electron entram por Pull Request com lista de conferência (instalador, atualização automática, ponte entre processos, teste de uso) e **sem ADR novo**, desde que fiquem sempre dentro dos 3 majors com suporte. Antes da entrega (Fase 9), o major em uso precisa estar dentro do suporte.
- Spring Boot 4.1 para 4.2 é mudança de minor, mas a especificação diz "4.1.x"; por isso depende de uma revisão da especificação (ver dúvida 4).

## Consequências

### Boas
- Uma base atual e coerente, em que Boot, Flyway, Testcontainers e driver já foram testados juntos pela equipe do Spring.
- Cada número tem fonte e data, então a próxima pessoa sabe de onde veio e o que checar de novo.
- Regra de atualização clara: patch e minor andam sozinhos; major pede decisão.

### Ruins ou custos
- O suporte aberto do Spring Boot 4.1 termina em 31/07/2027, antes de a dona completar um ano de uso. Será preciso migrar para a linha 4.2 (ou seguinte) e rever a especificação.
- O Electron 45 termina em 27/04/2027 (provisório): haverá pelo menos uma troca de major do Electron antes ou logo depois da entrega.
- TypeScript 6.0.3 e gerador 0.x são escolhas defensivas; a 7 e o gerador mudarão de situação em poucos meses e haverá retrabalho moderado.
- Dois Nodes diferentes: o de desenvolvimento (26) e o embutido no Electron (24.22). Um teste verde no Node 26 não prova o comportamento dentro do aplicativo.
- PostgreSQL 18: a imagem oficial mudou o diretório de dados padrão em relação ao 17. A tarefa 5 deve conferir a documentação da imagem ao montar o volume do Docker Compose.
- Quase nenhuma biblioteca de front-end publica data de fim de suporte. Isso foi registrado como "sem data publicada".

## Alternativas consideradas

- **Spring Boot 4.0.9:** o suporte aberto termina em 31/12/2026, em semanas. Descartado.
- **Spring Boot 4.2.0 (candidata a versão 2):** sai em 30/11/2026 e contraria a especificação ("4.1.x"). Descartado por ora; ver dúvida 4.
- **Java 25 (LTS):** o Boot 4.1 aceita, mas a especificação fixa o Java 21 e o JDK 21 já está instalado. Fica para um ADR futuro.
- **Flyway 13.12.1:** é a mais nova, mas sobrepõe a versão gerenciada pelo Boot. Descartada para a fundação.
- **PostgreSQL 17.11:** funciona, mas perde suporte em 11/2029, um ano antes da 18, e não há migração a fazer num projeto novo.
- **Maven 4.0.0-rc-7:** ainda é candidata a versão. Descartada.
- **Gradle:** a especificação escolheu Maven (§5).
- **Node 24 (suporte de manutenção):** é o Node que o Electron embute, o que alinharia desenvolvimento e aplicativo, mas o pedido foi "LTS ativo". Ver dúvida 5.
- **Electron 44 e 43:** suporte até 03/2027 e 01/2027. Descartados pelo prazo menor.
- **TypeScript 7.0.2:** quebra o lint hoje. Descartado por ora.
- **openapi-typescript 7.13.0:** declara compatibilidade só com TypeScript 5. **Orval 8.47.0:** gera clientes de acesso à API mais pesados do que o necessário aqui. Ambos descartados.

## Seções da especificação relacionadas

§5 (tecnologia), §11 (compatibilidade de versão da API e atualização), §13.4 (testes com Testcontainers), §14 (repositório, CI, Dependabot, ambiente de desenvolvimento), §15 (Fase 0).

## Dúvidas

Nenhuma delas foi decidida sozinha; para cada uma, o texto acima mostra a proposta usada enquanto a resposta não vem.

1. **TypeScript 6.0.3 ou 7.0.2?** A proposta usa a 6.0.3 porque o typescript-eslint não aceita a 7. Rever quando o lint aceitar.
2. **Gerador de cliente:** o @hey-api/openapi-ts é 0.x e pode mudar entre minors. Alternativas: openapi-typescript (exige TypeScript 5) ou Orval.
3. **Flyway 12.4.0 (a do Boot) ou 13.12.1?** A proposta segue o Boot.
4. **Spring Boot 4.2:** o suporte aberto da linha 4.1 acaba em 31/07/2027 e a 4.2.0 sai em 30/11/2026. Migrar exige mudar o "4.1.x" da §5 da especificação. Quem decide é o responsável.
5. **Node 26 (LTS ativo, o pedido) ou Node 24 (o que o Electron embute, em manutenção)?**
6. **electron-builder 26.18.0 (tag `latest`) ou 26.18.1 (tag `v26`)?** O registro marca versões diferentes em cada tag; a proposta segue a `latest` (e electron-updater 6.8.11 pelo mesmo motivo).
7. **Exceção do Electron** (majors sem ADR novo): precisa de aprovação.
8. **Datas de suporte:** só o Spring Boot, a Adoptium, o PostgreSQL, o Node e o Electron publicam. Para as demais bibliotecas não há data a registrar.

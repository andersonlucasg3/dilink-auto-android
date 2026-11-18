# Como abrir uma issue (guia de operação de issues)

> Este documento é a norma de operação de issues para todos os Agents do repositório e **deve ser seguido obrigatoriamente**.
> Complementar: como abrir PR em [PR_GUIDE.md](./PR_GUIDE.md); visão geral do fluxo em [AGENTS.md](../../AGENTS.md) §3.

## Definição: a issue é a menor unidade delegável

- Uma issue = uma coisa que pode ser delegada de forma independente a um subagent, validada de forma independente e fechada de forma independente.
- **Só abra a issue, não faça a correção**: problemas encontrados em inspeção, teste em veículo real e revisão de código (não importa o tamanho) devem primeiro virar issue; se será corrigido, quem corrige e quando, é decidido na discussão da issue.
- Agir sem abrir issue = trabalho sem rastro. Qualquer "acho que há um problema aqui" precisa antes virar uma issue com evidências.

## Quando abrir

| Cenário | Tipo de título | Label |
|---|---|---|
| Funcionalidade quebrada, comportamento fora do esperado | `bug` | `bug` |
| Defeito no próprio teste/build/gate (falso positivo, falso negativo, dependência de ambiente) | `bug` ou `fix` | `bug` |
| Necessidade de funcionalidade nova / capacidade nova | `feat` | `enhancement` |
| Refatoração (sem alterar comportamento externo) | `refactor` | depende do conteúdo |
| Otimização de desempenho | `perf` | `enhancement` |
| Inclusão/revisão de documentação | `docs` | `documentation` |
| Norma de colaboração / convenção de processo | `[规范]` | `documentation` |
| Pesquisa / relatório de viabilidade | `[存档]` | `documentation` |
| Registro geral de lacunas, resumo de entregas | `[追踪]` / `[记录]` | `documentation` |

Toda issue nova leva prefixo de tipo; não use títulos como "tem um problema" ou "olha isso".

## Padrão de título

```
type(scope): resumo em uma linha
```

- O resumo deve deixar claro "o que quebrou / o que se quer", de modo que ler a lista permita entender o estado do projeto.
- `scope` indica o subsistema: `vd-server` / `app-server` / `app-client` / `app-desktop` / `protocol` / `ci` / `testes` / `documentação` etc., e pode ser omitido.
- Correções posteriores com a mesma causa raiz referenciam o número uma da outra e indicam no título qual geração é (ex.: `…(#3 segundo caso)`).
- Exemplos:
  - `bug(vd-server): veículo real "fluido → travamento progressivo → congelamento → desconexão e reinício": escrita síncrona bloqueante do frame codificado no socket; a contrapressão para o VD e o timeout de escrita de 5s encerram a sessão`
  - `bug(app-server): autocorreção de tela preta dispara re-handshake indevidamente em imagem estática de 3 segundos e a sessão é reconstruída repetidamente`
  - `[规范] Alterações no repositório seguem sempre issue → plan → worktree → PR → revalidação`

## Os quatro elementos do corpo (nenhum pode faltar)

### 1. Sintoma

A seção de sintoma de uma issue de comportamento deve entregar **um comando de reprodução que possa ser colado diretamente no PowerShell**, além da saída esperada, da saída real e da condição de disparo:

| Campo | Requisito |
|---|---|
| Comando de reprodução | Um comando que possa ser colado e executado por inteiro (com os prefixos necessários como `.\gradlew.bat`, `adb`, `.vnev`); se envolver vários passos, primeiro consolide em uma única linha ou em um bloco de código colável por inteiro |
| Saída esperada | O que se deve ver em condições normais (texto original ou linhas-chave) |
| Saída real | O que se vê agora; cole o **texto original** (incluindo código de saída, mensagem de erro e linhas de log) |
| Condição de disparo / frequência | Em qual dispositivo/ambiente/condição ocorre; se intermitente, informe "ocorrências / total"; se sempre reproduz, escreva que é sempre reproduzível |

- Issues que não são de comportamento (somente documentação, somente refatoração, somente norma) escrevem na seção de sintoma `Comando de reprodução: nenhum (não envolve comportamento em tempo de execução)`.
- Contraexemplos: "às vezes trava", "não parece certo" — o que não é reproduzível deve primeiro receber `needs-repro`.

### 2. Cadeia de evidências

- Toda afirmação precisa de `arquivo:linha` ou de um comando reproduzível; para logs, informe o caminho e as linhas-chave.
- Problemas em veículo real / app desktop devem, preferencialmente, citar o texto original do log de campo e indicar de qual build o log veio (debug/release, se a chave de diagnóstico estava ligada).
- **Sem evidência, não há correção** (AGENTS.md §2): finding sem evidência é considerada fictícia.

### 3. Área de impacto

- Quem é afetado (qual módulo/qual fluxo/qual tipo de dispositivo), em que condição dispara, e severidade com **P+número**: P0 bloqueante / P1 comum / P2 canto.
- A ordem de correção é definida por prioridade na issue, não é definida na implementação.
- Issue grande que não cabe em uma coisa só: primeiro defina as **subseções (P+letra)** e depois delegue — uma subseção = um PR que pode ser mesclado de forma independente; a nomenclatura está em [PR_GUIDE.md](./PR_GUIDE.md) "Padrão de divisão e nomenclatura".

### 4. Candidatos de correção + sugestão de regressão

- Indique a direção sugerida (podem ser vários candidatos, não se exige a solução perfeita) e deixe claro **com qual teste/verificação a recorrência será travada**.
- Todo defeito corrigido deve deixar uma "lápide": no teste unitário, no comentário ou no documento, escreva qual issue ela impede de recorrer.

## Comando de reprodução e `needs-repro`

Se, ao abrir a issue, não for possível fornecer um comando de reprodução colável, aplique `needs-repro`; não escreva "às vezes acontece":

- **Descobridor**: inclua a linha de comando e as saídas esperada/real e então remova o label; se realmente não for possível incluir, explique na issue por que não é reproduzível (timing, ambiente de veículo real, permissões) e deixe o mantenedor decidir se rebaixa para item de observação.
- **Corretor**: ao escrever o plan, é obrigatório preencher antes uma linha de baseline de aceitação — `Reprodução com o comando da issue: sucesso / falha (cole a saída original)`. Falha na reprodução indica que a descrição da issue não condiz com o estado atual: devolva ao descobridor para complementar e não inicie a implementação; somente reproduzindo com sucesso remova `needs-repro` e comece a alterar.
- **Revalidação**: após o merge, rode o mesmo comando novamente; veja "Fechamento: issues de comportamento dependem de revalidação".

## Fechamento: issues de comportamento dependem de revalidação, não do merge

- PRs somente de documentação / somente de refatoração podem usar `Closes #NN`, e fecham no merge.
- PRs de comportamento (`fix` / `feat` / `perf` e qualquer PR que altere código de tempo de execução) usam `Refs #NN` no corpo; após o merge, a issue continua aberta e recebe `needs-live-verify`, aguardando a revalidação.
- A **revalidação** é executada pelo descobridor (ou por um agent de verificação em contexto totalmente novo), seguindo o template de [PR_GUIDE.md](./PR_GUIDE.md) "Após o merge: conferência e revalidação", informando ambiente, comando de reprodução, saída real e evidência em veículo real; somente com a conclusão "sintoma desapareceu" o label é removido e a issue é fechada. Se ainda reproduzir, remova o label, mantenha a issue e complemente as evidências.
- O corretor não pode revalidar o próprio trabalho.

## O trio de registro (usar issues como memória do projeto)

| Tipo | Uso |
|---|---|
| `[记录]` | Snapshot do estado pós-merge: resumo do que foi entregue em uma etapa + resultado da verificação em veículo real, servindo de baseline do estado atual para issues seguintes |
| `[存档]` | Relatório de viabilidade/pesquisa: conclusões e lista de riscos, citado por issues seguintes como origem |
| `[追踪]` | Registro geral de lacunas: item a item, liste "feito / não feito / avaliado e recomendado não fazer, encerrado", evitando que a mesma lacuna seja reaberta repetidamente |

Resultado de verificação em veículo real não vai para o texto longo da issue: preencha a documentação do repositório via docs PR e deixe na issue apenas a conclusão e o link.

## Antipadrões

- Colocar várias coisas em uma issue — não é possível fechar de forma independente; divida por subseções.
- Só conclusão, sem evidência — devolva para complementar.
- Tratar a issue como histórico de chat ou boletim de progresso — o processo vai nos comentários do PR, a conclusão vai na issue.
- Fazer push direto na main depois de corrigir — é obrigatório passar por PR.
- Issue de comportamento fechada no merge com `Closes` — o sintoma não foi verificado pelo comando de reprodução; o fechamento é apenas a alegação do corretor.
- Sintoma descrito apenas como "às vezes falha", sem comando colável — aplique `needs-repro` primeiro.

## Template (pronto para copiar)

```markdown
## Sintoma

- Comando de reprodução: `<um comando que possa ser colado diretamente no PowerShell>`
- Saída esperada: <o que se deve ver em condições normais>
- Saída real: <o que se vê agora, cole o texto original>
- Condição de disparo / frequência: <dispositivo / ambiente / condição / intermitente ou sempre reproduzível; se não for de comportamento, escreva "nenhum (não envolve comportamento em tempo de execução)">

## Cadeia de evidências

- `<arquivo>:<linha>`: <o que aquele código/configuração demonstra>
- Log: <caminho> linhas-chave <...>

## Área de impacto

<quem é afetado, em que condição, severidade P0/P1/P2>

## Candidatos de correção

<diretriz sugerida; com qual teste/verificação travar a recorrência; o que explicitamente não será feito>
```

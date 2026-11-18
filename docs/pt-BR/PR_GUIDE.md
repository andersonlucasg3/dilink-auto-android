# Como abrir um PR (guia de operação de PRs)

> Este documento é a norma de operação de PRs para todos os Agents do repositório e **deve ser seguido obrigatoriamente**.
> Complementar: como abrir issue em [ISSUE_GUIDE.md](./ISSUE_GUIDE.md); visão geral do fluxo em [AGENTS.md](../../AGENTS.md) §3.

## Definição: o PR é a menor unidade revisável

- Uma issue, um PR: o PR é o veículo de implementação da issue. **O merge só significa que a alteração entrou na `main`, não significa que o sintoma desapareceu** — PRs de comportamento usam `Refs #NN` e a issue é fechada depois da revalidação do descobridor.
- Princípio de divisão: cada PR deve passar na CI de forma independente, ser mesclado de forma independente e ser revertido de forma independente; se não der para dividir, a fronteira não foi pensada — volte à issue e complemente o plan.

## Forma de fechamento: Closes ou Refs

| Palavra-chave | PR aplicável | Momento do fechamento |
|---|---|---|
| `Closes #NN` | Somente documentação (`docs/`, `*.md`), somente refatoração (sem alterar comportamento externo) | Fecha no merge |
| `Refs #NN` | Comportamento: `fix` / `feat` / `perf`, e qualquer PR que toque caminhos de código de tempo de execução | O merge só entra na fila de aceitação (`needs-live-verify`); só fecha depois da revalidação do descobridor |

Critério em uma frase: **depois do merge ainda é preciso observar o sintoma mais uma vez em dispositivo real para confirmar que funciona** — se precisa, é de comportamento.

- Todo PR de uma issue com múltiplas subseções usa `Refs #NN`; após o merge de todas as subseções, a revalidação é feita de uma vez e a issue é fechada em uma única vez.
- Um mesmo PR que altera documentação e comportamento ao mesmo tempo: trate como de comportamento.
- Depois do merge de um PR de comportamento, o corretor aplica `needs-live-verify` na issue; após a revalidação passar, o revalidador remove o label e fecha a issue.

## Padrão de divisão e nomenclatura

| Dimensão | Identificador | Semântica |
|---|---|---|
| Subseção (etapa) | `P+letra`: `PA`/`PB`/`PC`… | Divide uma issue grande em etapas ordenadas e mescláveis de forma independente; **uma subseção = um PR** |
| Prioridade | `P+número`: `P0`/`P1`/`P2` | P0 bloqueante (segurança/corrupção de dados/fluxo principal inoperante), P1 defeito em caminho comum, P2 canto e melhoria |

- Primeiro defina a ordem de implementação por prioridade; dentro da mesma prioridade, avance na ordem alfabética das subseções; a numeração das subseções é única dentro da issue e não reinicia em função da prioridade.
- Formato de referência: `#<issue>-P<letra>`, por exemplo `#3-PA`.
- Onde fica: o título da subseção na issue é escrito como `### PA · <título> (P1)`; branches de múltiplas subseções são escritas como `<type>/<issue>-p<letra>-<slug>`; o título do PR e o commit levam o sufixo "subseção PA".

## Branch

```powershell
git worktree add .tmp/wt-<issue> -b <type>/<issue>-<slug> main
```

- Crie a partir da `main` mais recente; **use git worktree, não troque de branch no workspace principal**.
- `type` alinhado ao tipo da issue: `fix` / `feat` / `docs` / `refactor` / `chore` / `test` / `perf`.
- Depois de usar o worktree, execute `git worktree remove .tmp/wt-<issue>`; não o deixe fermentando em `.tmp/`.

## Título e commit

- Título do PR: `type(scope): resumo (issue #NN)` — branch, título e commit levam o número da issue nos três lugares.
  - Exemplo: `fix(vd-server): frames codificados passam a ser enviados por fila limitada; a contrapressão de rede não para mais o VD (issue #3)`
- A mensagem de commit também leva o número da issue; um PR pode ter vários commits, mas cada commit deve se explicar por si só; não use `wip` / `fix`.

## Seções do corpo

Estrutura fixa; nenhuma seção obrigatória pode faltar:

| Seção | Obrigatória | Conteúdo |
|---|---|---|
| `Closes #NN` / `Refs #NN` | Sim | Escolha uma das duas conforme a seção anterior; coloque no fim do corpo |
| `## Problema` | Sim | Reproduz o sintoma da issue + causa raiz (com `arquivo:linha`) |
| `## Alterações` | Sim | Liste as alterações por arquivo, explicando o "porquê" de cada arquivo |
| `## Testes` | Sim | Cole a saída original de `.\gradlew.bat` (linhas de sucesso/falha das tarefas); não escreva "passou" |
| `## Validação de baseline` | Obrigatória ao corrigir issue de teste/gate | Veja abaixo |
| `## Área de impacto` | Sim | Mudança de comportamento, compatibilidade, partes que explicitamente não mudam |
| `## Conferência de aceitação` | Obrigatória ao dividir issue grande | Confira item a item por subseção; itens não concluídos devem ser listados explicitamente e convertidos em issue nova |

### A validação de baseline é requisito rígido

Ao corrigir issue do tipo "falso positivo ou falso negativo de teste/gate", é obrigatório provar que a regressão realmente travou o defeito:

- A mesma verificação na **base sem patch** (worktree pristine) deve ficar vermelha, e a assinatura da falha deve coincidir com o sintoma da issue;
- Depois do patch, deve ficar verde;
- Cole as duas saídas no PR. Só "ficou verde com o patch" sem "fica vermelho sem o patch" = verde falso; não é mesclado.

## Autoverificação antes de abrir o PR

1. Rode localmente o build e os testes unitários alinhados à CI e confirme que o verde não vem de estado privado da máquina de desenvolvimento:
   ```powershell
   .\gradlew.bat :app-client:assembleDebug :app-client:testDebugUnitTest :protocol-core:test
   ```
   Se a alteração envolver outros módulos, rode também os testes unitários do módulo correspondente (ex.: `:protocol-core:test :vd-server:test :app-server:test`).
2. **Antes da linha vermelha local, rode a baseline**: rode a mesma verificação de novo na base sem patch para confirmar se é problema de código ou de ambiente — problema de ambiente não entra na correção, mas deve ser registrado no PR.
3. Confirme que a branch parte da `main` mais recente e que, após `git fetch origin main`, não está atrasada.
4. Mudança de comportamento em veículo real/app desktop precisa vir com evidência de campo: linhas-chave do log, saída do comando de reprodução ou caminho do screenshot; só teste unitário não conta como comportamento validado.

## Condições de merge (só mescle se todas forem atendidas)

1. **CI totalmente verde e CI realmente executada**: valide por comando, não confie no "botão dá para clicar" a olho nu:

   ```powershell
   gh pr checks <NN>
   ```
   - Só é válido com saída **não vazia** (existe check) e **tudo success** (exit 0). Saída vazia = CI não rodou (veja "pré-requisito de habilitação de workflows" abaixo); **não mescle só porque o botão dá para clicar**.
   - "CI não rodou" é tão inválido quanto "CI vermelha"; o "gate equivalente local" não substitui mais a CI — a seção "## Testes" do corpo do PR deve colar **ao mesmo tempo** a saída original local do `.\gradlew.bat` e o link do CI run; faltando um dos dois, considera-se não verificado.
   - O `.github/workflows/build.yml` executa `:app-client:assembleDebug :app-client:testDebugUnitTest :protocol-core:test` tanto em push quanto em pull_request, rodando em `ubuntu-latest` (runner GitHub-hosted; o repositório não tem runner self-hosted).

   **Pré-requisito de habilitação de workflows**: se as Actions do repositório estiverem desabilitadas (caso típico de repositório criado como fork), o GitHub não executa o workflow automaticamente. Antes do primeiro uso, o owner precisa ter clicado no botão "I understand my workflows, go ahead and enable them" na página Actions do repositório; caso contrário, qualquer PR/push fica com zero check (`gh pr checks` com saída vazia). A habilitação vale apenas para eventos futuros e **não roda retroativamente** PRs já mesclados. `actions/permissions` mostrar `enabled:true` e "página Actions não habilitada" são dois switches diferentes; o primeiro ser true não significa que o segundo foi clicado.

   A `main` atualmente não tem proteção de branch; tudo acima depende de execução consciente.
2. **Sem conflito**: o GitHub mostra que é mesclável. Havendo conflito, não force o merge — resolva no worktree com `git merge origin/main` (ou rebase), rode o build e os testes unitários de novo e só então faça push.
3. Quando aplicável, existe a seção `## Validação de baseline`.
4. PR de comportamento não fecha a issue diretamente após o merge; siga a conferência e a revalidação abaixo.

## Após o merge: conferência e revalidação

A conferência é a autoverificação do corretor, a revalidação é a aceitação pelo descobridor; as duas precisam ser feitas:

- **Alegação vs realidade, item a item (corretor)**: cada item que o PR diz ter feito deve ser reconferido de forma independente após o merge (`git status`, existência dos arquivos, saída original de build/teste unitário).
- **Pendência vira issue nova**: omissões, danos colaterais e itens sem fechamento em dispositivo real encontrados na conferência devem ser convertidos, um a um, em issues novas de acompanhamento; o merge não encerra, a conferência encerra.
- **Revalidação de issue de comportamento (descobridor / agent de verificação em contexto totalmente novo)**: o corretor aplica `needs-live-verify` na issue após o merge; o revalidador publica um comentário no template abaixo e só com a conclusão "sintoma desapareceu" remove o label e fecha. Se ainda reproduzir, remova o label, mantenha a issue e complemente as evidências. **O revalidador não pode ser o Agent autor deste PR**.
  - **A independência é julgada por Agent, não por conta do GitHub**: o fluxo de desenvolvimento deste repositório é conduzido por Agents, e cada Agent equivale a um desenvolvedor. O Agent autor do PR e o Agent autor da issue/revalidador precisam ser Agents diferentes; a mesma conta do GitHub (ex.: `ID-VerNe`) pode corresponder a rodadas diferentes de Agents diferentes e **não configura autoassinatura**, nem exige criar outra conta de reviewer. O comentário de revalidação deve registrar o identificador do Agent/sessão, em vez de usar a igualdade de conta para presumir conflito de papel.
- Em caso de recorrência com a mesma causa raiz, abra uma correção de segunda geração e referencie uma à outra; cada geração deixa uma lápide de teste.

### Template de revalidação

Publique no comentário da issue; nenhum campo pode faltar:

- **Ambiente**: `main` @ `<sha>`, dispositivo real local / CI, `<hora>`; quando envolver a central do veículo, informe o modelo do carro, a versão do sistema da central e a forma de conexão (USB / WiFi ADB / direta)
- **Comando de reprodução**: prefira reaproveitar exatamente o da seção "Sintoma" da issue
- **Saída esperada**: o que se deve ver após a correção
- **Saída real**: cole a saída original, não escreva só "normal"
- **Evidência em dispositivo real**: caminho do log e linhas-chave (ex.: `tex=/cb=` de `Pipeline:`, `drops=/inputFails=` de `[VideoDecoder]`), duração da observação contínua
- **Conclusão**: sintoma desapareceu / ainda reproduz (se ainda reproduz, cole a diferença)

## Template (pronto para copiar)

```markdown
## Problema

<reprodução do sintoma da issue + causa raiz, com `arquivo:linha`>

## Alterações

`<arquivo>`
- <ponto alterado + por quê>

## Testes

```
<saída original do .\gradlew.bat: linhas de sucesso/falha das tarefas>
```

Também passou localmente: <lista de testes unitários por módulo>.

## Área de impacto

<mudança de comportamento / compatibilidade / partes que explicitamente não mudam>

## Conferência de aceitação

- [x] <item de aceitação 1 da issue>
- [ ] <item não concluído, convertido em #NN>

Refs #NN
```

PR de comportamento mantém `Refs #NN` na última linha e, após o merge, segue "Após o merge: conferência e revalidação", aguardando a revalidação do descobridor; PR somente de documentação / somente de refatoração troca por `Closes #NN`.

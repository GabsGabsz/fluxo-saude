// Atualização periódica SEM sobreposição: só agenda a próxima depois que a atual termina;
// pausa enquanto a tela informa edição em andamento; nunca dispara escrita (só a função de
// leitura recebida). Falha de conexão é sinalizada como "dados possivelmente desatualizados".
// Pedido manual durante uma leitura em andamento não abre outra em paralelo: fica marcado e
// roda UMA vez logo depois (para que, após uma escrita, a tela reflita o estado novo).

export function criarAtualizador({ carregar, intervaloMs, emEdicao = () => false, aoMudarSituacao = () => {},
  agendar = (f, ms) => setTimeout(f, ms), cancelar = (id) => clearTimeout(id) }) {
  let temporizador = null;
  let atual = null;       // promessa da leitura em andamento (no máximo uma)
  let repetir = false;
  let parado = true;
  let falhou = false;

  async function ciclo() {
    temporizador = null;
    if (parado) return;
    if (emEdicao()) {
      aoMudarSituacao({ pausado: true, falhou });
      temporizador = agendar(ciclo, intervaloMs);
      return;
    }
    await executarAgora();
    if (!parado && temporizador === null) temporizador = agendar(ciclo, intervaloMs);
  }

  async function umaLeitura() {
    try {
      await carregar();
      falhou = false;
      return true;
    } catch (e) {
      // Resposta descartada (troca de unidade/saída) não é falha de atualização.
      if (!(e && e.name === 'RespostaDescartada')) falhou = true;
      return false;
    } finally {
      aoMudarSituacao({ pausado: false, falhou });
    }
  }

  function executarAgora() {
    if (atual) {
      repetir = true;
      return atual;
    }
    atual = (async () => {
      let ok;
      do {
        repetir = false;
        ok = await umaLeitura();
      } while (repetir);
      return ok;
    })().finally(() => { atual = null; });
    return atual;
  }

  return {
    iniciar() {
      if (!parado) return;
      parado = false;
      temporizador = agendar(ciclo, intervaloMs);
    },
    parar() {
      parado = true;
      repetir = false;
      if (temporizador !== null) cancelar(temporizador);
      temporizador = null;
    },
    /** Atualização manual (botão "Atualizar", após escrita): nunca em paralelo com outra leitura. */
    atualizarAgora: executarAgora,
    emAndamento: () => atual !== null,
  };
}

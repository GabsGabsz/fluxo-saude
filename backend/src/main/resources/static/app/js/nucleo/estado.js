// Estado da sessão em MEMÓRIA (nunca em armazenamento do navegador). A "geração" muda ao
// entrar, sair, trocar de unidade ou ter a sessão revogada: tudo o que foi carregado antes
// deixa de valer e respostas atrasadas são descartadas pelo cliente de API.

export function criarEstado() {
  let geracao = 0;
  let sessao = null;      // resposta de /api/sessao
  let catalogo = null;    // resposta de /api/catalogo (unidade ativa)
  let unidades = [];      // /api/sessao/unidades
  const ouvintes = new Set();

  const avisar = () => ouvintes.forEach((f) => f());

  return {
    geracao: () => geracao,
    sessao: () => sessao,
    catalogo: () => catalogo,
    unidades: () => unidades,
    pode: (permissao) => !!(sessao && !sessao.deveTrocarSenha && sessao.permissoes.includes(permissao)),
    /** Nova sessão ou nova unidade ativa: invalida tudo o que pertencia à anterior. */
    definirSessao(nova) {
      const mudouContexto = !sessao || !nova || sessao.usuarioId !== nova.usuarioId
        || sessao.unidadeAtiva !== nova.unidadeAtiva;
      if (mudouContexto) {
        geracao += 1;
        catalogo = null;
      }
      sessao = nova;
      avisar();
    },
    /**
     * Antes de trocar de unidade: invalida já tudo o que está em voo (respostas que chegarem
     * depois são descartadas, mesmo que o servidor as tenha calculado com a unidade nova).
     */
    invalidar() {
      geracao += 1;
      catalogo = null;
      avisar();
    },
    definirCatalogo(c) { catalogo = c; avisar(); },
    definirUnidades(u) { unidades = Array.isArray(u) ? u : []; avisar(); },
    /** Sair / sessão revogada: descarta tudo. */
    limpar() {
      geracao += 1;
      sessao = null;
      catalogo = null;
      unidades = [];
      avisar();
    },
    aoMudar(f) { ouvintes.add(f); return () => ouvintes.delete(f); },
  };
}

/** Telas do menu e permissão exigida (o servidor recusa o resto de qualquer forma). */
export const TELAS = [
  { rota: 'torre', titulo: 'Torre de Controle', permissao: 'EPISODIO_VER' },
  { rota: 'travados', titulo: 'Pacientes travados', permissao: 'EPISODIO_VER' },
  { rota: 'abrir', titulo: 'Abrir episódio', permissao: 'EPISODIO_ABRIR' },
  { rota: 'plantao', titulo: 'Passagem de plantão', permissao: 'PLANTAO_GERENCIAR' },
  { rota: 'painel', titulo: 'Painel coletivo', permissao: 'PAINEL_COLETIVO_VER' },
  { rota: 'indicadores', titulo: 'Indicadores', permissao: 'INDICADORES_VER' },
  { rota: 'usuarios', titulo: 'Usuários', permissao: 'USUARIO_GERENCIAR' },
  { rota: 'regras', titulo: 'Regras de alerta', permissao: 'CONFIGURACAO_GERENCIAR' },
];

export function telasPermitidas(pode) {
  return TELAS.filter((t) => pode(t.permissao));
}

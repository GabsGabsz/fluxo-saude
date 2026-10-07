package br.fluxosaude.relatorio.dominio;

/**
 * Relatórios gerenciais (extensão aprovada do projeto, issue #9 — não é requisito da ERS
 * original). Cada tipo declara os filtros que fazem sentido para ele; filtro não suportado é
 * recusado (nunca ignorado em silêncio).
 */
public enum TipoRelatorio {
    /** Eventos do período × estoque no instante de referência. */
    RESUMO("Resumo gerencial da operação", false, false),
    /** Onde e há quanto tempo se espera: etapas, setores, bloqueios e pendências abertas. */
    GARGALOS("Gargalos por etapa, setor e categoria de bloqueio", true, true),
    /** Pendências criadas, encerradas e abertas; lista operacional só com acesso nominal. */
    PENDENCIAS("Acompanhamento de pendências", false, true),
    /** Período escolhido × período anterior de mesma duração. */
    EVOLUCAO("Evolução entre períodos comparáveis", false, false),
    /** Atualidade, registros retroativos e cobertura da informação. */
    QUALIDADE("Qualidade e atualidade dos registros", false, false);

    private final String titulo;
    private final boolean aceitaEtapa;
    private final boolean aceitaCategoria;

    TipoRelatorio(String titulo, boolean aceitaEtapa, boolean aceitaCategoria) {
        this.titulo = titulo;
        this.aceitaEtapa = aceitaEtapa;
        this.aceitaCategoria = aceitaCategoria;
    }

    public String titulo() {
        return titulo;
    }

    public boolean aceitaEtapa() {
        return aceitaEtapa;
    }

    public boolean aceitaCategoria() {
        return aceitaCategoria;
    }
}

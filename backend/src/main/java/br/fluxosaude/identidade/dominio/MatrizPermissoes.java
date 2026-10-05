package br.fluxosaude.identidade.dominio;

import static br.fluxosaude.identidade.dominio.Permissao.*;

import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Matriz padrão perfil → permissões, derivada da ERS §3 com mínimo privilégio.
 * É PONTO DE PARTIDA a validar com a instituição (V-06, RN-015): por isso fica isolada
 * aqui, coberta por testes de propriedades de segurança (ex.: transporte não vê casos).
 */
public final class MatrizPermissoes {

    private static final Map<Papel, Set<Permissao>> PADRAO = new EnumMap<>(Papel.class);

    static {
        PADRAO.put(Papel.ADMINISTRADOR, EnumSet.of(
                CONFIGURACAO_GERENCIAR, USUARIO_GERENCIAR, AUDITORIA_VER));
        PADRAO.put(Papel.COORDENACAO_FLUXO, EnumSet.of(
                EPISODIO_VER, EPISODIO_ABRIR, EPISODIO_ALTERAR, EPISODIO_ENCERRAR, PENDENCIA_GERENCIAR,
                OBSERVACAO_REGISTRAR, HORARIO_AJUSTAR, PACIENTE_RECONCILIAR, DIVERGENCIA_REGISTRAR,
                TRANSPORTE_VER_FILA, TRANSPORTE_ATUALIZAR, PLANTAO_GERENCIAR, PAINEL_COLETIVO_VER, INDICADORES_VER));
        PADRAO.put(Papel.ENFERMAGEM, EnumSet.of(
                EPISODIO_VER, EPISODIO_ABRIR, EPISODIO_ALTERAR, EPISODIO_ENCERRAR, PENDENCIA_GERENCIAR,
                OBSERVACAO_REGISTRAR, HORARIO_AJUSTAR, PLANTAO_GERENCIAR, PAINEL_COLETIVO_VER));
        PADRAO.put(Papel.MEDICO, EnumSet.of(
                EPISODIO_VER, EPISODIO_ALTERAR, EPISODIO_ENCERRAR, PENDENCIA_GERENCIAR,
                OBSERVACAO_REGISTRAR, PLANTAO_GERENCIAR, PAINEL_COLETIVO_VER));
        PADRAO.put(Papel.TRANSPORTE, EnumSet.of(TRANSPORTE_VER_FILA, TRANSPORTE_ATUALIZAR));
        // Direção: visão agregada/pseudonimizada; acesso nominal só mediante concessão explícita (ERS §3).
        PADRAO.put(Papel.DIRECAO, EnumSet.of(INDICADORES_VER, PAINEL_COLETIVO_VER));
        PADRAO.put(Papel.AUDITORIA, EnumSet.of(AUDITORIA_VER));
    }

    private MatrizPermissoes() {
    }

    public static Set<Permissao> permissoes(Collection<Papel> papeis) {
        EnumSet<Permissao> resultado = EnumSet.noneOf(Permissao.class);
        for (Papel p : papeis) {
            resultado.addAll(PADRAO.getOrDefault(p, Set.of()));
        }
        return Set.copyOf(resultado);
    }
}

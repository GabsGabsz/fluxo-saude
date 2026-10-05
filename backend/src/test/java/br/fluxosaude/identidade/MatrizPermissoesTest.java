package br.fluxosaude.identidade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.fluxosaude.identidade.dominio.AcessoNegadoException;
import br.fluxosaude.identidade.dominio.MatrizPermissoes;
import br.fluxosaude.identidade.dominio.Papel;
import br.fluxosaude.identidade.dominio.Permissao;
import br.fluxosaude.identidade.dominio.UsuarioAutenticado;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Propriedades de segurança da matriz padrão (ERS §3, mínimo privilégio). */
class MatrizPermissoesTest {

    private static Set<Permissao> de(Papel p) {
        return MatrizPermissoes.permissoes(List.of(p));
    }

    @Test
    @DisplayName("Transporte vê só a fila de transporte — nunca casos nominais")
    void transporte() {
        assertEquals(EnumSet.of(Permissao.TRANSPORTE_VER_FILA, Permissao.TRANSPORTE_ATUALIZAR), de(Papel.TRANSPORTE));
    }

    @Test
    @DisplayName("Direção e administração não têm acesso nominal por padrão")
    void semAcessoNominal() {
        assertFalse(de(Papel.DIRECAO).contains(Permissao.EPISODIO_VER));
        assertFalse(de(Papel.ADMINISTRADOR).contains(Permissao.EPISODIO_VER));
        assertFalse(de(Papel.AUDITORIA).contains(Permissao.EPISODIO_VER));
    }

    @Test
    @DisplayName("Ajuste manual de horário (RNF-017) é restrito")
    void ajusteRestrito() {
        for (Papel p : Papel.values()) {
            boolean pode = de(p).contains(Permissao.HORARIO_AJUSTAR);
            assertEquals(p == Papel.COORDENACAO_FLUXO || p == Papel.ENFERMAGEM, pode, p.name());
        }
    }

    @Test
    @DisplayName("Gestão de usuários e configuração só com ADMINISTRADOR")
    void administracao() {
        for (Papel p : Papel.values()) {
            assertEquals(p == Papel.ADMINISTRADOR, de(p).contains(Permissao.USUARIO_GERENCIAR), p.name());
            assertEquals(p == Papel.ADMINISTRADOR, de(p).contains(Permissao.CONFIGURACAO_GERENCIAR), p.name());
        }
    }

    @Test
    @DisplayName("Permissões valem só na unidade ativa e são suspensas até trocar a senha")
    void unidadeAtivaETrocaDeSenha() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UsuarioAutenticado u = new UsuarioAutenticado(UUID.randomUUID(), "ana", "Ana",
                Map.of(a, Set.of(Papel.ENFERMAGEM), b, Set.of(Papel.TRANSPORTE)), a, false);
        assertTrue(u.pode(Permissao.EPISODIO_VER));
        assertFalse(u.comUnidadeAtiva(b).pode(Permissao.EPISODIO_VER));
        assertThrows(IllegalArgumentException.class, () -> u.comUnidadeAtiva(UUID.randomUUID()));
        UsuarioAutenticado pendente = new UsuarioAutenticado(u.usuarioId(), "ana", "Ana", u.lotacoes(), a, true);
        assertFalse(pendente.pode(Permissao.EPISODIO_VER));
        assertThrows(AcessoNegadoException.class, () -> AcessoNegadoException.exigir(pendente, Permissao.EPISODIO_VER));
        assertTrue(pendente.senhaTrocada().pode(Permissao.EPISODIO_VER));
    }

    @Test
    void principalNaoExpoeDadosPessoais() {
        UUID id = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UsuarioAutenticado u = new UsuarioAutenticado(id, "ana.souza", "Ana Souza", Map.of(a, Set.of(Papel.MEDICO)), a, false);
        assertEquals(id.toString(), u.getName());
        assertFalse(u.toString().contains("Ana"));
        assertFalse(u.toString().contains("ana.souza"));
    }
}

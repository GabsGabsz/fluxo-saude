package br.fluxosaude.episodio.dominio;

import br.fluxosaude.compartilhado.RegraVioladaException;
import br.fluxosaude.compartilhado.Textos;
import java.time.LocalDate;
import java.util.regex.Pattern;

/**
 * Cadastro mínimo do paciente (RN-009): só o necessário para identificar o caso no fluxo.
 * Sem CPF, endereço, telefone ou qualquer dado clínico.
 */
public record NovoPaciente(String nome, LocalDate dataNascimento, String cns, String identificadorInstitucional) {

    private static final Pattern IDENTIFICADOR = Pattern.compile("^[A-Za-z0-9./-]{1,40}$");

    public NovoPaciente {
        nome = Textos.obrigatorio(nome, "Nome do paciente", 2, 200).replaceAll("\\s+", " ");
        cns = cns == null || cns.isBlank() ? null : cns.replaceAll("[\\s.-]", "");
        RegraVioladaException.exigir(cns == null || Cns.valido(cns), "CNS_INVALIDO", "CNS inválido");
        identificadorInstitucional = identificadorInstitucional == null || identificadorInstitucional.isBlank()
                ? null : identificadorInstitucional.strip();
        RegraVioladaException.exigir(identificadorInstitucional == null
                        || IDENTIFICADOR.matcher(identificadorInstitucional).matches(),
                "IDENTIFICADOR_INVALIDO", "Identificador institucional inválido");
        RegraVioladaException.exigir(dataNascimento == null
                        || (!dataNascimento.isBefore(LocalDate.of(1900, 1, 1)) && !dataNascimento.isAfter(LocalDate.now().plusDays(1))),
                "NASCIMENTO_INVALIDO", "Data de nascimento inválida");
    }
}

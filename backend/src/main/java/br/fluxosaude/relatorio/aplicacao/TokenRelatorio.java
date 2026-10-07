package br.fluxosaude.relatorio.aplicacao;

import br.fluxosaude.relatorio.dominio.Canonico;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Comprovante, emitido pelo servidor, de QUAL conjunto de dados foi entregue a QUEM: assinatura
 * do conjunto, tipo, unidade, usuário, filtros, instante de referência e emissão, protegidos por
 * HMAC-SHA256 com chave do servidor. Ao registrar uma exportação, o servidor confere o comprovante
 * — o registro de auditoria usa os filtros e a assinatura do comprovante, não o que o navegador
 * alegar. A chave é gerada a cada inicialização (comprovantes antigos deixam de valer: basta
 * recalcular o relatório).
 */
public final class TokenRelatorio {

    public record Dados(String assinatura, String tipo, UUID unidadeId, UUID usuarioId, LocalDate inicio, LocalDate fim,
                        UUID setor, UUID etapa, String categoria, Instant referencia, Instant emitidoEm, boolean nominal,
                        int linhas) {
        public Dados {
            Objects.requireNonNull(assinatura);
            Objects.requireNonNull(tipo);
            Objects.requireNonNull(unidadeId);
            Objects.requireNonNull(usuarioId);
            Objects.requireNonNull(referencia);
            Objects.requireNonNull(emitidoEm);
        }

        String carga() {
            return Canonico.linha("v1", assinatura, tipo, unidadeId, usuarioId, inicio, fim, setor, etapa, categoria,
                    referencia, emitidoEm, nominal, linhas);
        }
    }

    private final byte[] chave;

    public TokenRelatorio(byte[] chave) {
        if (chave == null || chave.length < 32) {
            throw new IllegalArgumentException("chave HMAC de pelo menos 256 bits");
        }
        this.chave = chave.clone();
    }

    public String emitir(Dados d) {
        String carga = d.carga();
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        return b64.encodeToString(carga.getBytes(StandardCharsets.UTF_8)) + "." + b64.encodeToString(mac(carga));
    }

    /** Dados do comprovante, se autêntico (formato e HMAC); vazio caso contrário. */
    public Optional<Dados> verificar(String token) {
        if (token == null || token.length() > 2048) {
            return Optional.empty();
        }
        int ponto = token.indexOf('.');
        if (ponto <= 0 || ponto != token.lastIndexOf('.')) {
            return Optional.empty();
        }
        try {
            Base64.Decoder dec = Base64.getUrlDecoder();
            String carga = new String(dec.decode(token.substring(0, ponto)), StandardCharsets.UTF_8);
            byte[] recebido = dec.decode(token.substring(ponto + 1));
            if (!MessageDigest.isEqual(mac(carga), recebido)) {
                return Optional.empty();
            }
            return Optional.of(ler(carga));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Dados ler(String carga) {
        // Os campos não contêm '|' (códigos, ids, datas, instantes, hexadecimal): divisão direta.
        String[] c = carga.split("\\|", -1);
        if (c.length != 14 || !"v1".equals(c[0])) {
            throw new IllegalArgumentException("comprovante em formato desconhecido");
        }
        return new Dados(c[1], c[2], UUID.fromString(c[3]), UUID.fromString(c[4]), data(c[5]), data(c[6]), uuid(c[7]),
                uuid(c[8]), nulo(c[9]), Instant.parse(c[10]), Instant.parse(c[11]), Boolean.parseBoolean(c[12]),
                Integer.parseInt(c[13]));
    }

    private static String nulo(String s) {
        return "∅".equals(s) ? null : s;
    }

    private static UUID uuid(String s) {
        return nulo(s) == null ? null : UUID.fromString(s);
    }

    private static LocalDate data(String s) {
        return nulo(s) == null ? null : LocalDate.parse(s);
    }

    private byte[] mac(String carga) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(chave, "HmacSHA256"));
            return m.doFinal(carga.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}

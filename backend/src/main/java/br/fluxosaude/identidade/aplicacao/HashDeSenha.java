package br.fluxosaude.identidade.aplicacao;

/** Porta para o algoritmo de hash (Argon2id na infraestrutura). */
public interface HashDeSenha {

    String gerar(String senha);

    /** Comparação em tempo constante feita pela implementação. */
    boolean confere(String senha, String hash);

    /** Hash válido de uma senha aleatória: usado para igualar o tempo de resposta. */
    String hashFicticio();
}

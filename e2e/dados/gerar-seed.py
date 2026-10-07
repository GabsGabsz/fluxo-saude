#!/usr/bin/env python3
"""Gera o SQL de dados FICTÍCIOS do E2E a partir de fixtures.json (saída padrão).

Executado só no banco efêmero do CI (ou local de desenvolvimento), como fluxo_owner, DEPOIS
das migrações. As senhas viram hash Argon2id no MESMO formato da aplicação
({argon2@SpringSecurity_v5_8}, m=19456 KiB, t=2, p=1, sal 16 B, hash 32 B) — a senha em
claro nunca vai para o banco.

    pip install argon2-cffi==25.1.0
    python3 e2e/dados/gerar-seed.py | psql -v ON_ERROR_STOP=1 -U fluxo_owner -d fluxo
"""
import json
import pathlib
import uuid

from argon2 import PasswordHasher, Type

PREFIXO = "{argon2@SpringSecurity_v5_8}"
HASHER = PasswordHasher(time_cost=2, memory_cost=19456, parallelism=1, hash_len=32, salt_len=16, type=Type.ID)


def q(texto):
    """Literal SQL seguro (os dados são fixos e fictícios, mas o escape é feito mesmo assim)."""
    if texto is None:
        return "NULL"
    return "'" + str(texto).replace("'", "''") + "'"


def main():
    dados = json.loads((pathlib.Path(__file__).parent / "fixtures.json").read_text(encoding="utf-8"))
    ids_unidade = {u["codigo"]: uuid.uuid4() for u in dados["unidades"]}
    ids_usuario = {u["login"]: uuid.uuid4() for u in dados["usuarios"]}
    out = ["\\set ON_ERROR_STOP on", "BEGIN;"]
    for u in dados["unidades"]:
        uid = ids_unidade[u["codigo"]]
        out.append(f"INSERT INTO fluxo.unidade (id, codigo, nome, tipo, fuso_horario) VALUES "
                   f"({q(uid)}, {q(u['codigo'])}, {q(u['nome'])}, 'UPA', {q(u['fuso'])});")
        out.append(f"SELECT fluxo.provisionar_unidade({q(uid)});")
        for codigo, nome in u["setores"]:
            out.append(f"INSERT INTO fluxo.setor (id, unidade_id, codigo, nome) VALUES "
                       f"({q(uuid.uuid4())}, {q(uid)}, {q(codigo)}, {q(nome)});")
    for usr in dados["usuarios"]:
        hash_senha = PREFIXO + HASHER.hash(usr["senha"])
        out.append("INSERT INTO fluxo.usuario (id, login, nome, senha_hash, deve_trocar_senha, unidade_gestora_id) "
                   f"VALUES ({q(ids_usuario[usr['login']])}, {q(usr['login'])}, {q(usr['nome'])}, {q(hash_senha)}, "
                   f"{'true' if usr.get('deveTrocarSenha') else 'false'}, {q(ids_unidade[usr['gestora']])});")
        for unidade, papeis in usr["lotacoes"].items():
            for papel in papeis:
                out.append(f"INSERT INTO fluxo.lotacao (usuario_id, unidade_id, papel) VALUES "
                           f"({q(ids_usuario[usr['login']])}, {q(ids_unidade[unidade])}, {q(papel)}::fluxo.papel);")
    # Pacientes: o gatilho de auditoria exige um usuário no contexto da transação.
    autor = ids_usuario[dados["usuarios"][0]["login"]]
    out.append(f"SELECT set_config('fluxo.usuario_id', {q(autor)}, true);")
    for p in dados["pacientes"]:
        out.append(f"INSERT INTO fluxo.paciente (id, unidade_id, nome, cns) VALUES "
                   f"({q(uuid.uuid4())}, {q(ids_unidade[p['unidade']])}, {q(p['nome'])}, {q(p['cns'])});")
    out.append("COMMIT;")
    print("\n".join(out))


if __name__ == "__main__":
    main()

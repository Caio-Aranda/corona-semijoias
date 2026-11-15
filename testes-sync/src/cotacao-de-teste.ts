import { rodarSql } from './maquina-do-tempo';

/**
 * A cotação do dia para o fechamento do acerto (tarefa 8.10). A API só a busca na internet (agenda da dona), e o fechar
 * recusa sem ela ("Busque a cotação do dia"). Aqui a gente grava uma cotação de hoje (Brasília) no banco descartável, com
 * valores fixos; é a única coluna de dados que o teste cria direto e nunca toca o banco de desenvolvimento.
 */
export function sqlDaCotacaoDeHoje(): string {
  return `INSERT INTO corona.cotacoes (id, data_cotacao, usd_compra, usd_venda, usd_paridade_compra, usd_paridade_venda,
    pyg_compra, pyg_venda, pyg_paridade_compra, pyg_paridade_venda, buscada_em, fonte)
  VALUES (uuidv7(), (now() AT TIME ZONE 'America/Sao_Paulo')::date, 5.00, 5.00, 1, 1, 7000, 7000, 1, 1, now(), 'teste')
  ON CONFLICT ON CONSTRAINT cotacoes_data_unica DO NOTHING;`;
}

export function registrarCotacaoDeHoje(): Promise<void> {
  return rodarSql(sqlDaCotacaoDeHoje());
}

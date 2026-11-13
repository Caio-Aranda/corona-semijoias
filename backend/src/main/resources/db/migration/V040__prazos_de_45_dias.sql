-- Tarefa 6.13 (respostas da dona de 13/11/2026, ESPECIFICACAO v1.13): a garantia passa a ser de 45 dias,
-- tanto para desistência quanto para defeito. A V035 não é editada: esta migração só muda a chave que
-- ainda está no valor padrão antigo (7 e 30), porque a dona pode ter ajustado o prazo pela tela.

UPDATE corona.configuracoes
   SET valor_inteiro = 45
 WHERE chave = 'prazo_arrependimento_dias' AND tipo = 'INTEIRO' AND valor_inteiro = 7;

UPDATE corona.configuracoes
   SET valor_inteiro = 45
 WHERE chave = 'prazo_defeito_dias' AND tipo = 'INTEIRO' AND valor_inteiro = 30;

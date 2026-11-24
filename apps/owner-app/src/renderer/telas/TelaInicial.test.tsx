// @vitest-environment jsdom
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { PedidoDeChamada, PonteDoCorona, ResultadoDeChamada } from '../../shared/ponte';
import { ProvedorDaCotacao } from '../nucleo/cotacao';
import { TelaInicial } from './TelaInicial';

const COTACAO = {
  disponivel: true,
  cotacao: { dataCotacao: '2026-11-17', dolar: '5.50', guarani: '0.00075', deDiaAnterior: false },
};

const PAINEL = {
  totais: {
    novasMaletas: { quantidade: 2, pecas: 30 },
    reposicoes: { quantidade: 1, pecas: 10 },
    acerto: { quantidade: 4 },
    total: { vendido: { emReais: '1250', emDolar: '227.27', emGuarani: '1666666' } },
  },
};

function ponteCom(respostas: Record<string, ResultadoDeChamada>) {
  const ponte = {
    chamarApi: vi.fn((p: PedidoDeChamada) =>
      Promise.resolve(respostas[p.operacao] ?? { ok: true as const, dados: {} }),
    ),
  } as unknown as PonteDoCorona;
  return ponte;
}

function mostrar(ponte: PonteDoCorona) {
  return render(
    <ProvedorDaCotacao ponte={ponte}>
      <TelaInicial ponte={ponte} nome="Maria" />
    </ProvedorDaCotacao>,
  );
}

beforeEach(() => {
  window.location.hash = '';
});

describe('tela inicial da dona (visual novo)', () => {
  it('cumprimenta, mostra a data por extenso e a cotação do dia', async () => {
    mostrar(ponteCom({ 'cotacao.hoje': { ok: true, dados: COTACAO } }));
    expect(screen.getByRole('heading', { name: 'Olá, Maria.' })).toBeInTheDocument();
    expect(screen.getByText(/\d{1,2} de \w+ de \d{4}/)).toBeInTheDocument();
    const cotacao = await screen.findByLabelText('Cotação do dia');
    expect(await within(cotacao).findByText(/1 US\$ =/)).toBeInTheDocument();
    expect(within(cotacao).getByText(/R\$ 5,50/)).toBeInTheDocument();
    expect(within(cotacao).getByText(/R\$ 1 =/)).toBeInTheDocument();
  });

  it('sem cotação, diz isso em vez de dar erro', async () => {
    mostrar(ponteCom({ 'cotacao.hoje': { ok: true, dados: { disponivel: false } } }));
    expect(await screen.findByText('Sem cotação do dia')).toBeInTheDocument();
  });

  it('cada atalho tem uma linha dizendo para que serve e leva à mesma rota de antes', async () => {
    const u = userEvent.setup();
    mostrar(ponteCom({}));
    const atalhos = screen.getByRole('navigation', { name: 'O que você quer fazer?' });
    expect(within(atalhos).getAllByRole('button')).toHaveLength(6);
    expect(within(atalhos).getByText('separar as peças para o representante')).toBeInTheDocument();
    await u.click(within(atalhos).getByRole('button', { name: 'Fazer acerto' }));
    expect(window.location.hash).toBe('#/acertos/lista');
    await u.click(within(atalhos).getByRole('button', { name: 'Painel da semana' }));
    expect(window.location.hash).toBe('#/painel/semana');
    await u.click(within(atalhos).getByRole('button', { name: 'Receber compra' }));
    expect(window.location.hash).toBe('#/estoque/receber-compra');
  });

  it('nenhum atalho fica em "Em breve": montar maleta, cadastrar produto e relatórios abrem as telas que existem', async () => {
    const u = userEvent.setup();
    mostrar(ponteCom({}));
    const atalhos = screen.getByRole('navigation', { name: 'O que você quer fazer?' });
    await u.click(within(atalhos).getByRole('button', { name: 'Montar maleta' }));
    expect(window.location.hash).toBe('#/maletas/montar');
    await u.click(within(atalhos).getByRole('button', { name: 'Cadastrar produto' }));
    expect(window.location.hash).toBe('#/cadastros/produtos');
    await u.click(within(atalhos).getByRole('button', { name: 'Relatórios' }));
    expect(window.location.hash).toBe('#/areas/vendas');
    expect(screen.queryByText('Em breve.')).not.toBeInTheDocument();
  });

  it('o resumo da semana mostra o que o servidor devolveu e cada número abre a tela de onde veio', async () => {
    const u = userEvent.setup();
    mostrar(
      ponteCom({
        'painel.semanal': { ok: true, dados: PAINEL },
        'avisos.listar': { ok: true, dados: { avisos: [] } },
      }),
    );
    const resumo = screen.getByRole('region', { name: 'Resumo da semana' });
    expect(await within(resumo).findByText('R$ 1.250,00')).toBeInTheDocument();
    expect(within(resumo).getByText('3')).toBeInTheDocument(); // 2 novas + 1 reposição
    expect(within(resumo).getByText('4')).toBeInTheDocument();
    await u.click(within(resumo).getByRole('button', { name: /Acertos da semana/ }));
    expect(window.location.hash).toBe('#/acertos/lista');
    await u.click(within(resumo).getByRole('button', { name: /Maletas da semana/ }));
    expect(window.location.hash).toBe('#/maletas/lista');
  });

  it('se faltar dado, o cartão mostra um traço e não há erro', async () => {
    mostrar(ponteCom({ 'painel.semanal': { ok: false, motivo: 'erro', mensagem: 'x' } }));
    const resumo = screen.getByRole('region', { name: 'Resumo da semana' });
    expect(await within(resumo).findAllByText('—')).not.toHaveLength(0);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('sem avisos, mostra uma mensagem tranquila', async () => {
    mostrar(ponteCom({ 'avisos.listar': { ok: true, dados: { avisos: [] } } }));
    expect(await screen.findByText('Tudo em dia.')).toBeInTheDocument();
    expect(screen.getByText('Não há nada esperando por você agora.')).toBeInTheDocument();
  });

  it('aviso com problema fica vermelho e tem botão de ação', async () => {
    const u = userEvent.setup();
    mostrar(
      ponteCom({
        'avisos.listar': {
          ok: true,
          dados: {
            avisos: [
              { titulo: 'Envios com problema', texto: 'Há 2 envios.', tipo: 'ENVIOS_COM_PROBLEMA' },
            ],
          },
        },
      }),
    );
    const alerta = await screen.findByRole('alert');
    expect(alerta).toHaveTextContent('Envios com problema');
    await u.click(within(alerta).getByRole('button', { name: 'Abrir' }));
    expect(window.location.hash).toBe('#/computadores/envios');
  });
});

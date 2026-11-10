import { Botao, CabecalhoDeMarca, JanelaDeConfirmacao, MenuLateral } from '@corona/ui';
import { useState } from 'react';
import type { PonteDoCorona } from '../../shared/ponte';
import { RodapeDaCotacao } from '../nucleo/cotacao';
import { temAlteracoesNaoSalvas } from '../nucleo/guarda-de-saida';
import { MENU, rotuloDaRota, useRota, type Rota } from '../nucleo/rotas';
import { TEXTOS } from '../textos';
import { TelaEmBreve } from './TelaEmBreve';
import { TelaInicial } from './TelaInicial';
import { TelaDeCadastro } from './cadastros/TelaDeCadastro';
import { ListaDeCompras } from './compras/ListaDeCompras';
import { TelaReceberCompra } from './compras/TelaReceberCompra';
import { ListaDeLancamentos } from './estoque/ListaDeLancamentos';
import { ListaDoEstoque } from './estoque/ListaDoEstoque';
import { TelaContagem } from './estoque/TelaContagem';
import { TelaProdutos } from './produtos/TelaProdutos';
import { TelaDeRelatorio } from './relatorios/TelaDeRelatorio';
import { RELATORIOS } from '../nucleo/relatorios';
import { NOMES_DOS_RELATORIOS, type NomeDoRelatorio } from '../../shared/relatorios';
import { TelaUsuarios } from './usuarios/TelaUsuarios';
import { TelaVendedores } from './vendedores/TelaVendedores';

export interface EstruturaProps {
  ponte: PonteDoCorona;
  nome: string | null;
  aoSair: () => void | Promise<void>;
}

function Conteudo({
  rota,
  ponte,
  nome,
}: {
  rota: Rota;
  ponte: PonteDoCorona;
  nome: string | null;
}) {
  switch (rota) {
    case 'inicio':
      return <TelaInicial ponte={ponte} nome={nome} />;
    case 'cadastros/produtos':
      return <TelaProdutos ponte={ponte} />;
    case 'estoque/posicao':
      return <ListaDoEstoque ponte={ponte} modo="posicao" />;
    case 'estoque/acabando':
      return <ListaDoEstoque ponte={ponte} modo="acabando" />;
    case 'estoque/entradas-e-saidas':
      return <ListaDeLancamentos ponte={ponte} />;
    case 'estoque/contagem':
      return <TelaContagem ponte={ponte} />;
    case 'estoque/receber-compra':
      return (
        <TelaReceberCompra
          ponte={ponte}
          aoVerLista={() => {
            window.location.hash = '/estoque/compras';
          }}
        />
      );
    case 'estoque/compras':
      return (
        <ListaDeCompras
          ponte={ponte}
          aoReceber={() => {
            window.location.hash = '/estoque/receber-compra';
          }}
        />
      );
    case 'pessoas/vendedores':
      return <TelaVendedores ponte={ponte} />;
    case 'pessoas/usuarios':
      return <TelaUsuarios ponte={ponte} />;
    case 'cadastros/fornecedores':
      return <TelaDeCadastro ponte={ponte} cadastro="fornecedores" />;
    case 'cadastros/categorias':
      return <TelaDeCadastro ponte={ponte} cadastro="categorias" />;
    case 'cadastros/pracas':
      return <TelaDeCadastro ponte={ponte} cadastro="pracas" />;
    case 'cadastros/semanas':
      return <TelaDeCadastro ponte={ponte} cadastro="semanas" />;
    case 'cadastros/restricoes':
      return <TelaDeCadastro ponte={ponte} cadastro="restricoes" />;
    case 'pessoas/clientes':
      return <TelaDeCadastro ponte={ponte} cadastro="clientes" />;
    default:
      if (rota.startsWith('relatorios/')) {
        const qual = rota.slice('relatorios/'.length) as NomeDoRelatorio;
        if (
          (NOMES_DOS_RELATORIOS as readonly string[]).includes(qual) &&
          !RELATORIOS[qual].soAbreDeUmaLinha
        ) {
          return <TelaDeRelatorio ponte={ponte} relatorio={qual} usuario={nome} />;
        }
      }
      return <TelaEmBreve nome={rotuloDaRota(rota)} />;
  }
}

/** Cabeçalho, menu lateral, a tela escolhida e a linha da cotação do dia no rodapé. */
export function Estrutura({ ponte, nome, aoSair }: EstruturaProps) {
  const [rota, irPara] = useRota();
  // Menu escolhido com alterações por salvar: pergunta antes de sair da tela.
  const [destino, setDestino] = useState<Rota | null>(null);
  return (
    <div className="gestao-pagina">
      <CabecalhoDeMarca
        nomeDoApp="Corona Gestão"
        acoes={
          <>
            {nome ? <span>{nome} </span> : null}
            <Botao onClick={() => void aoSair()}>Sair</Botao>
          </>
        }
      />
      <div className="gestao-corpo">
        <MenuLateral
          grupos={MENU}
          atual={rota}
          rotulo={TEXTOS.menu.rotulo}
          aoEscolher={(chave) =>
            temAlteracoesNaoSalvas() ? setDestino(chave as Rota) : irPara(chave as Rota)
          }
        />
        <main className="gestao-conteudo">
          {/* A chave faz a tela começar do zero ao trocar de item: senhas e formulários não vazam. */}
          <Conteudo key={rota} rota={rota} ponte={ponte} nome={nome} />
          <RodapeDaCotacao />
        </main>
      </div>
      {destino ? (
        <JanelaDeConfirmacao
          titulo={TEXTOS.vendedores.sair.titulo}
          rotuloConfirmar={TEXTOS.vendedores.sair.sair}
          rotuloCancelar={TEXTOS.vendedores.sair.continuar}
          perigosa
          aoCancelar={() => setDestino(null)}
          aoConfirmar={() => {
            irPara(destino);
            setDestino(null);
          }}
        >
          {TEXTOS.vendedores.sair.texto}
        </JanelaDeConfirmacao>
      ) : null}
    </div>
  );
}

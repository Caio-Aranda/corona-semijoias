import { Botao, CabecalhoDeMarca, JanelaDeConfirmacao, MenuLateral } from '@corona/ui';
import { useEffect, useState } from 'react';
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
import { TelaAcertos } from './acertos/TelaAcertos';
import { TelaClientesAlterados } from './sincronizacao/TelaClientesAlterados';
import { TelaComputadores } from './sincronizacao/TelaComputadores';
import { TelaEnviosComProblema } from './sincronizacao/TelaEnviosComProblema';
import { TelaVendasBloqueadas } from './sincronizacao/TelaVendasBloqueadas';
import { TelaDevolucoes } from './devolucoes/TelaDevolucoes';
import { TelaCreditos } from './devolucoes/TelaCreditos';
import { TelaGarantia } from './devolucoes/TelaGarantia';
import { TelaPedidosDeAutorizacao } from './devolucoes/TelaPedidosDeAutorizacao';
import { TelaTrocas } from './devolucoes/TelaTrocas';
import { TelaConferenciaDoDinheiro } from './conciliacoes/TelaConferenciaDoDinheiro';
import { TelaConferencia } from './devolucoes/TelaConferencia';
import { TelaMaletas } from './maletas/TelaMaletas';
import { TelaMontarMaleta } from './maletas/TelaMontarMaleta';
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
  maletaParaAbrir,
  aoAbrirMaleta,
}: {
  rota: Rota;
  ponte: PonteDoCorona;
  nome: string | null;
  maletaParaAbrir: string | null;
  aoAbrirMaleta: (id: string) => void;
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
    case 'maletas/lista':
      return <TelaMaletas ponte={ponte} abrirId={maletaParaAbrir} />;
    case 'maletas/montar':
      return <TelaMontarMaleta ponte={ponte} />;
    case 'acertos/lista':
      return <TelaAcertos ponte={ponte} />;
    case 'pessoas/vendedores':
      return <TelaVendedores ponte={ponte} />;
    case 'computadores/lista':
      return <TelaComputadores ponte={ponte} />;
    case 'computadores/envios':
      return <TelaEnviosComProblema ponte={ponte} />;
    case 'computadores/vendas':
      return <TelaVendasBloqueadas ponte={ponte} />;
    case 'computadores/clientes':
      return <TelaClientesAlterados ponte={ponte} />;
    case 'devolucoes/autorizar':
      return <TelaPedidosDeAutorizacao ponte={ponte} />;
    case 'devolucoes/revisar':
      return <TelaDevolucoes ponte={ponte} modo="revisar" />;
    case 'devolucoes/lista':
      return <TelaDevolucoes ponte={ponte} modo="todas" />;
    case 'devolucoes/trocas':
      return <TelaTrocas ponte={ponte} />;
    case 'devolucoes/creditos':
      return <TelaCreditos ponte={ponte} />;
    case 'devolucoes/garantia':
      return <TelaGarantia ponte={ponte} />;
    case 'relatorios/devolucao-conferencia':
      return <TelaConferencia ponte={ponte} />;
    case 'conciliacoes/dinheiro':
      return <TelaConferenciaDoDinheiro ponte={ponte} />;
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
          return (
            <TelaDeRelatorio
              ponte={ponte}
              relatorio={qual}
              usuario={nome}
              aoAbrirMaleta={aoAbrirMaleta}
            />
          );
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
  // Maleta pedida por um relatório: a tela Maletas abre a ficha dela; ao sair da tela, esquece.
  const [maletaParaAbrir, setMaletaParaAbrir] = useState<string | null>(null);
  useEffect(() => {
    if (rota !== 'maletas/lista') setMaletaParaAbrir(null);
  }, [rota]);
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
          <Conteudo
            key={rota}
            rota={rota}
            ponte={ponte}
            nome={nome}
            maletaParaAbrir={maletaParaAbrir}
            aoAbrirMaleta={(id) => {
              setMaletaParaAbrir(id);
              irPara('maletas/lista');
            }}
          />
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

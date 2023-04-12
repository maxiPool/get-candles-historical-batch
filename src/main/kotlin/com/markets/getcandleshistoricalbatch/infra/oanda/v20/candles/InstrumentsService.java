package com.markets.getcandleshistoricalbatch.infra.oanda.v20.candles;

import com.markets.getcandleshistoricalbatch.infra.oanda.v20.candles.resource.OandaRestResource;
import com.markets.getcandleshistoricalbatch.infra.oanda.v20.properties.V20Properties;
import com.oanda.v20.primitives.Instrument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class InstrumentsService {

  private final OandaRestResource resource;
  private final V20Properties properties;

  public List<Instrument> findAll() {
    try {
      return resource.getInstruments(properties.accountId().toString()).instruments();
    } catch (Exception e) {
      log.error("Error while trying to getInstruments", e);
    }
    return null;
  }

}

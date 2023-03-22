package com.markets.getcandleshistoricalbatch.infra.oanda.v20.candles;

import com.markets.getcandleshistoricalbatch.infra.oanda.v20.candles.csvutil.CsvCandle;
import com.oanda.v20.instrument.Candlestick;
import com.oanda.v20.pricing_common.PriceValue;
import com.oanda.v20.primitives.DateTime;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.time.Instant;

import static com.markets.getcandleshistoricalbatch.infra.oanda.v20.model.Rfc3339.YMDHMS_FORMATTER;
import static org.mapstruct.MappingConstants.ComponentModel.SPRING;

@Mapper(componentModel = SPRING)
public interface CandlestickMapper {

  String RFC3339_IN_SECONDS = "RFC3339_IN_SECONDS";

  @Mapping(target = "open", source = "mid.o")
  @Mapping(target = "high", source = "mid.h")
  @Mapping(target = "low", source = "mid.l")
  @Mapping(target = "close", source = "mid.c")
  @Mapping(target = "isComplete", source = "complete")
  @Mapping(target = "time", source = "time", qualifiedByName = RFC3339_IN_SECONDS)
  CsvCandle oandaCandleToCsvCandle(Candlestick candlestick);

  default Double priceValueToDouble(PriceValue priceValue) {
    return priceValue.doubleValue();
  }

  default Integer booleanToInteger(Boolean bool) {
    return bool ? 1 : 0;
  }

  @Named(RFC3339_IN_SECONDS)
  default DateTime dateTimeInSeconds(DateTime dateTime) {
    return new DateTime(
        YMDHMS_FORMATTER.format(
            Instant.parse(dateTime.toString())));
  }

}

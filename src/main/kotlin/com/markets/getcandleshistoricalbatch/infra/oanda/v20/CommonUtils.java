package com.markets.getcandleshistoricalbatch.infra.oanda.v20;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

@UtilityClass
@Slf4j
public class CommonUtils {

  public void printJson(Object object) {
    try {
      var mapper = getObjectMapper();
      var json = mapper.writeValueAsString(object);
      System.out.println(json);
    } catch (JsonProcessingException e) {
      log.error("Error while printing JSON", e);
    }
  }

  private ObjectMapper getObjectMapper() {
    var mapper = new ObjectMapper();
    mapper.registerModule(new JavaTimeModule());
    return mapper;
  }

}

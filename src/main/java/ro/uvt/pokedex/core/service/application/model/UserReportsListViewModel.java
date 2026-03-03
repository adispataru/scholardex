package ro.uvt.pokedex.core.service.application.model;

import ro.uvt.pokedex.core.model.reporting.IndividualReport;

import java.util.List;

public record UserReportsListViewModel(List<IndividualReport> individualReports) {
}

/**
 * Copyright (c) 2015, CodiLime Inc.
 *
 * Owner: Grzegorz Swatowski
 */

'use strict';

function DistributionCategoricalChart() {
  return {
    restrict: 'E',
    templateUrl: 'app/reports/charts/distribution-categorical-chart.html',
    replace: true,
    scope: {
      'data': '='
    },
    controller: () => {},
    controllerAs: 'distributionCategoricalChart',
    bindToController: true
  };
}

exports.inject = function (module) {
  module.directive('distributionCategoricalChart', DistributionCategoricalChart);
};

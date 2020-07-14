def test_clarity_running(clarity_config):
    """ Test that first clarity page loads. """
    clarity_config.selenium_driver.get(clarity_config.url)
    assert clarity_config.selenium_driver.title == "CasperLabs Clarity - Home"



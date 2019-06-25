import logging
import os

class LoggingMixin(object):
	def __init__(self, *args, **kwargs):
		self.logger = logging.getLogger(__name__)
		self.logger.setLevel(os.environ.get('CL_LOG_LEVEL', 'INFO'))
		super(LoggingMixin, self).__init__(*args, **kwargs)

	@property
	def log_level(self):
		return logging.getLevelName(self.logger.getEffectiveLevel())

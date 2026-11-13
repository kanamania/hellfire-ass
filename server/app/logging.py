import logging


class SafeAccessFilter(logging.Filter):
    def filter(self, record):
        # Preserve access logging without recording webhook verification query secrets.
        if record.args and isinstance(record.args, tuple) and len(record.args) == 5:
            args = list(record.args)
            args[2] = str(args[2]).split("?", 1)[0]
            record.args = tuple(args)
        return True

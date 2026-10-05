import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import build


class SigningInputTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.key = self.root / 'keys/mipad.p12'
        self.key.parent.mkdir()

        root_patch = patch.object(build, 'ROOT', self.root)
        root_patch.start()
        self.addCleanup(root_patch.stop)

        environment_patch = patch.dict(os.environ, {
            'MIPAD_KEYSTORE_PASSWORD': '',
            'MIPAD_KEY_PASSWORD': '',
        })
        environment_patch.start()
        self.addCleanup(environment_patch.stop)

        run_patch = patch.object(build, 'run')
        self.build_run = run_patch.start()
        self.addCleanup(run_patch.stop)

    def test_keystore_required(self):
        with self.assertRaises(FileNotFoundError) as error:
            build.main()

        self.assertIn(str(self.key), str(error.exception))
        self.build_run.assert_not_called()

    def test_keystore_password_required(self):
        self.key.touch()
        with self.assertRaisesRegex(ValueError, 'MIPAD_KEYSTORE_PASSWORD'):
            build.main()

        self.build_run.assert_not_called()

    def test_key_password_required(self):
        self.key.touch()
        os.environ['MIPAD_KEYSTORE_PASSWORD'] = self.id()
        with self.assertRaisesRegex(ValueError, 'MIPAD_KEY_PASSWORD'):
            build.main()

        self.build_run.assert_not_called()


if __name__ == '__main__':
    unittest.main()

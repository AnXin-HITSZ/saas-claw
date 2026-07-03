import unittest

from openclaw.tools.shell.guard import classify_shell_command
from openclaw.tools.shell.parser import detect_shell_dialect, parse_shell_command, tokenize_command


class ShellParserTests(unittest.TestCase):
    def test_detects_explicit_shell_dialect(self):
        self.assertEqual(detect_shell_dialect("echo hi", explicit="powershell"), "powershell")

    def test_tokenizes_bash_quotes(self):
        self.assertEqual(tokenize_command('rg "hello world" docs', "bash"), ("rg", "hello world", "docs"))

    def test_parses_compound_command_ast(self):
        ast = parse_shell_command('git status && rg "hello"', dialect="bash")

        self.assertEqual(ast.dialect, "bash")
        self.assertEqual(len(ast.segments), 2)
        self.assertEqual(ast.segments[0].argv, ("git", "status"))
        self.assertEqual(ast.segments[0].connector_after, "&&")
        self.assertEqual(ast.segments[1].argv, ("rg", "hello"))

    def test_classifies_readonly_mutation_dangerous_and_unknown(self):
        self.assertEqual(classify_shell_command("git status", dialect="bash").safety, "readonly")
        self.assertEqual(classify_shell_command("git add .", dialect="bash").safety, "mutation")
        self.assertEqual(classify_shell_command("rm -rf .", dialect="bash").safety, "dangerous")
        self.assertEqual(classify_shell_command("py -m pip install demo", dialect="cmd").safety, "unknown")

    def test_redirection_makes_readonly_command_mutating(self):
        classification = classify_shell_command("echo hello > out.txt", dialect="bash")

        self.assertEqual(classification.safety, "mutation")
        self.assertTrue(classification.requires_approval)


if __name__ == "__main__":
    unittest.main()
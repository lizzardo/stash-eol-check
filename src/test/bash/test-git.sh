#!/bin/sh

. ./common_functions.sh

test_bad_eol_commit
test_good_eol_commit
test_new_clear_branch
test_new_good_unclear_branch
test_new_bad_unclear_branch
test_new_mixed_unclear_branch
test_good_archive
test_bad_archive

using FluentAssertions;
using System;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using Xunit;
using Xunit.Abstractions;

namespace Sds.ChemicalFileParser.Tests
{
    public class ValidSdfWithCorruptedRecordsTestFixture
    {
        public Guid UserId { get; } = Guid.NewGuid();
        public Guid BlobId { get; }
        public string Bucket { get; }
        public Guid Id { get; } = Guid.NewGuid();
        public Guid CorrelationId { get; } = Guid.NewGuid();

        public ValidSdfWithCorruptedRecordsTestFixture(ChemicalFileParserTestHarness harness)
        {
            Bucket = UserId.ToString();
            BlobId = harness.UploadBlob(Bucket, "DrugBank_50_corrupted.sdf").Result;
            harness.ParseFile(Id, BlobId, Bucket, UserId, CorrelationId).Wait();
        }
    }

    [Collection("Chemical File Parser Test Harness")]
    public class ValidSdfWithCorruptedRecordsTests : ChemicalFileParserTest, IClassFixture<ValidSdfWithCorruptedRecordsTestFixture>
    {
        private Guid CorrelationId;
        private string Bucket;
        private Guid UserId;
        private Guid Id;

        public ValidSdfWithCorruptedRecordsTests(ChemicalFileParserTestHarness harness, ITestOutputHelper output, ValidSdfWithCorruptedRecordsTestFixture initFixture) : base(harness, output)
        {
            Id = initFixture.Id;
            CorrelationId = initFixture.CorrelationId;
            Bucket = initFixture.Bucket;
            UserId = initFixture.UserId;
        }

        [Fact]
        public void SdfParsing_ValidSdfFileWithCorruptedRecords_ReceivedEventsShouldContainValidData()
        {
            var recordParsedEvents = Harness.GetRecordParsedEventsList(Id);
            recordParsedEvents.Should().HaveCount(42);
            foreach (var evn in recordParsedEvents)
            {
                evn.FileId.Should().Be(Id);
                evn.UserId.Should().Be(UserId);
                evn.CorrelationId.Should().Be(CorrelationId);
            }

            var recordParseFailedEvents = Harness.GetRecordParseFailedEventsList(Id);
            recordParseFailedEvents.Should().HaveCount(5);
            foreach (var recordParseFailed in recordParseFailedEvents)
            {
                recordParseFailed.Should().NotBeNull();
                recordParseFailed.CorrelationId.Should().Be(CorrelationId);
                recordParseFailed.FileId.Should().Be(Id);
                recordParseFailed.UserId.Should().Be(UserId);
            }

            var fileParsedEvn = Harness.GetFileParsedEvent(Id);
            fileParsedEvn.Id.Should().Be(Id);
            fileParsedEvn.UserId.Should().Be(UserId);
            fileParsedEvn.CorrelationId.Should().Be(CorrelationId);
            fileParsedEvn.FailedRecords.Should().Be(5);
            fileParsedEvn.ParsedRecords.Should().Be(42);
            fileParsedEvn.TotalRecords.Should().Be(47);

            var fileParseFailedEvn = Harness.GetFileParseFailedEvent(Id);
            fileParseFailedEvn.Should().BeNull();
        }

        [Fact]
        public async Task SdfParsing_ValidSdfFileWithCorruptedRecords_UploadedBlobsContainNotEmptyData()
        {
            var recordParsedEvents = Harness.GetRecordParsedEventsList(Id);
            foreach (var evn in recordParsedEvents)
            {
                var blobInfo = await Harness.BlobStorage.GetFileInfo(evn.BlobId, Bucket);
                blobInfo.Should().NotBeNull();
                Path.GetExtension(blobInfo.FileName).Should().BeEquivalentTo(".mol");
                blobInfo.Length.Should().BeGreaterThan(0);
                blobInfo.ContentType.ToLower().Should().BeEquivalentTo("chemical/x-mdl-molfile");
            }
        }
    }
}
